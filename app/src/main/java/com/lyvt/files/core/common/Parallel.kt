package com.lyvt.files.core.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicInteger

/** Runs [fn] for all [items] on every CPU core, keeps order, drops nulls; [progress] gets the done-count about every 10 items. */
suspend fun <T, R : Any> parMap(items: List<T>, progress: (Int) -> Unit = {}, fn: (T) -> R?): List<R> = coroutineScope {
    val done = AtomicInteger()
    val d = Dispatchers.Default.limitedParallelism(maxOf(2, Runtime.getRuntime().availableProcessors()))
    items.map { x -> async(d) { runCatching { fn(x) }.getOrNull().also { val n = done.incrementAndGet(); if (n % 10 == 0) progress(n) } } }.awaitAll().filterNotNull()
}
