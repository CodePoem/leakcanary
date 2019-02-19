/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.leakcanary;

import java.io.File;
import java.lang.ref.ReferenceQueue;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

import static com.squareup.leakcanary.HeapDumper.RETRY_LATER;
import static com.squareup.leakcanary.Preconditions.checkNotNull;
import static com.squareup.leakcanary.Retryable.Result.DONE;
import static com.squareup.leakcanary.Retryable.Result.RETRY;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Watches references that should become weakly reachable. When the {@link RefWatcher} detects that
 * a reference might not be weakly reachable when it should, it triggers the {@link HeapDumper}.
 *
 * <p>This class is thread-safe: you can call {@link #watch(Object)} from any thread.
 */
public final class RefWatcher {

  public static final RefWatcher DISABLED = new RefWatcherBuilder<>().build();

  /**
   * 观察执行器
   */
  private final WatchExecutor watchExecutor;
  /**
   * 调试控制
   */
  private final DebuggerControl debuggerControl;
  /**
   * Gc触发器
   */
  private final GcTrigger gcTrigger;
  /**
   * “堆转储”操作者
   */
  private final HeapDumper heapDumper;
  /**
   * “堆转储“分析监听者
   */
  private final HeapDump.Listener heapdumpListener;
  /**
   * “堆转储”信息建造者
   */
  private final HeapDump.Builder heapDumpBuilder;
  /**
   * 留存的弱引用对象key
   */
  private final Set<String> retainedKeys;
  /**
   * 弱引用对象队列
   */
  private final ReferenceQueue<Object> queue;

  RefWatcher(WatchExecutor watchExecutor, DebuggerControl debuggerControl, GcTrigger gcTrigger,
      HeapDumper heapDumper, HeapDump.Listener heapdumpListener, HeapDump.Builder heapDumpBuilder) {
    this.watchExecutor = checkNotNull(watchExecutor, "watchExecutor");
    this.debuggerControl = checkNotNull(debuggerControl, "debuggerControl");
    this.gcTrigger = checkNotNull(gcTrigger, "gcTrigger");
    this.heapDumper = checkNotNull(heapDumper, "heapDumper");
    this.heapdumpListener = checkNotNull(heapdumpListener, "heapdumpListener");
    this.heapDumpBuilder = heapDumpBuilder;
    retainedKeys = new CopyOnWriteArraySet<>();
    queue = new ReferenceQueue<>();
  }

  /**
   * Identical to {@link #watch(Object, String)} with an empty string reference name.
   *
   * @see #watch(Object, String)
   */
  public void watch(Object watchedReference) {
    watch(watchedReference, "");
  }

  /**
   * Watches the provided references and checks if it can be GCed. This method is non blocking,
   * the check is done on the {@link WatchExecutor} this {@link RefWatcher} has been constructed
   * with.
   *
   * @param referenceName An logical identifier for the watched object.
   */
  public void watch(Object watchedReference, String referenceName) {
    if (this == DISABLED) {
      return;
    }
    checkNotNull(watchedReference, "watchedReference");
    checkNotNull(referenceName, "referenceName");
    // 记录开始观察时间（毫秒）
    // System.nanoTime与System.currentTimeMillis的区别
    // System.nanoTime相比System.currentTimeMillis提供相对精确的计时，但是不能用它来计算当前日期
    final long watchStartNanoTime = System.nanoTime();
    String key = UUID.randomUUID().toString();
    retainedKeys.add(key);
    // 构造带UUID key的弱引用对象
    final KeyedWeakReference reference =
        new KeyedWeakReference(watchedReference, key, referenceName, queue);

    // 确认弱引用对象回收同步
    ensureGoneAsync(watchStartNanoTime, reference);
  }

  /**
   * LeakCanary will stop watching any references that were passed to {@link #watch(Object, String)}
   * so far.
   */
  public void clearWatchedReferences() {
    retainedKeys.clear();
  }

  boolean isEmpty() {
    removeWeaklyReachableReferences();
    return retainedKeys.isEmpty();
  }

  HeapDump.Builder getHeapDumpBuilder() {
    return heapDumpBuilder;
  }

  Set<String> getRetainedKeys() {
    return new HashSet<>(retainedKeys);
  }

  private void ensureGoneAsync(final long watchStartNanoTime, final KeyedWeakReference reference) {
    watchExecutor.execute(new Retryable() {
      @Override public Retryable.Result run() {
        return ensureGone(reference, watchStartNanoTime);
      }
    });
  }

  @SuppressWarnings("ReferenceEquality") // Explicitly checking for named null.
  Retryable.Result ensureGone(final KeyedWeakReference reference, final long watchStartNanoTime) {
    // 记录开始gc的时间（毫秒）
    long gcStartNanoTime = System.nanoTime();
    // 计算观察期时长（毫秒）
    long watchDurationMs = NANOSECONDS.toMillis(gcStartNanoTime - watchStartNanoTime);

    // 移除弱可达引用
    removeWeaklyReachableReferences();

    if (debuggerControl.isDebuggerAttached()) {
      // The debugger can create false leaks.
      // 调试器可能生成假的内存泄漏
      return RETRY;
    }
    if (gone(reference)) {
      // 如果弱可达引用不存在 说明不存在内存泄漏
      return DONE;
    }
    // 执行gc
    gcTrigger.runGc();
    // 移除弱可达引用结果确认
    removeWeaklyReachableReferences();
    if (!gone(reference)) {
      // 记录开始“转储堆”的时间（毫秒）
      long startDumpHeap = System.nanoTime();
      // 计算gc期时长（毫秒）
      long gcDurationMs = NANOSECONDS.toMillis(startDumpHeap - gcStartNanoTime);

      // 执行“堆转储”操作
      File heapDumpFile = heapDumper.dumpHeap();
      if (heapDumpFile == RETRY_LATER) {
        // Could not dump the heap.
        return RETRY;
      }
      // // 计算“堆转储”期时长（毫秒）
      long heapDumpDurationMs = NANOSECONDS.toMillis(System.nanoTime() - startDumpHeap);

      // 构造“堆转储”信息
      HeapDump heapDump = heapDumpBuilder.heapDumpFile(heapDumpFile).referenceKey(reference.key)
          .referenceName(reference.name)
          .watchDurationMs(watchDurationMs)
          .gcDurationMs(gcDurationMs)
          .heapDumpDurationMs(heapDumpDurationMs)
          .build();

      // 执行“堆转储”文件分析
      heapdumpListener.analyze(heapDump);
    }
    return DONE;
  }

  /**
   * 判断弱可达引用是否消失
   * @param reference 弱可达引用
   * @return 弱可达引用是否消失
   */
  private boolean gone(KeyedWeakReference reference) {
    return !retainedKeys.contains(reference.key);
  }

  /**
   * 移除弱可达引用
   */
  private void removeWeaklyReachableReferences() {
    // WeakReferences are enqueued as soon as the object to which they point to becomes weakly
    // reachable. This is before finalization or garbage collection has actually happened.
    KeyedWeakReference ref;
    // poll，remove区别：
    // remove() 和 poll() 方法都是从队列中删除第一个元素。remove() 的行为与 Collection 接口的版本相似，
    // 但是新的 poll() 方法在用空集合调用时不是抛出异常，只是返回 null。因此新的方法更适合容易出现异常条件的情况。
    while ((ref = (KeyedWeakReference) queue.poll()) != null) {
      retainedKeys.remove(ref.key);
    }
  }
}
