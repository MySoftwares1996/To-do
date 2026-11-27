package com.mysoftwares.todo

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One shared background thread for network and file work (screens no longer create their own). */
object AppExecutors {
    val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "office-todo-io").apply { isDaemon = true } }
}
