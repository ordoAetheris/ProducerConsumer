package com.ordoAetheris.drafts.solution;


//public class ExampleWorkQueue <T>{
//
//    private final Queue<T> queue = new ArrayDeque<>();;
//    private boolean closed = false;
//
//    private final ReentrantLock lock = new ReentrantLock();
//    private final Condition notEmpty = lock.newCondition();
//
//
//    public void put(T item){
//        if (item == null) throw new IllegalArgumentException();
//        lock.lock();
//        try {
//            if (closed) throw new IllegalStateException();
//            queue.offer(item);
//            notEmpty.signal();
//        } finally {
//            lock.unlock();
//        }
//    }
//    public T take() throws InterruptedException {
//        lock.lock();
//        T result = null;
//        try {
//            while (!closed && queue.isEmpty()) notEmpty.await();
//            if (closed && queue.isEmpty()) return null;
//            result = queue.poll();
//        } finally {
//            lock.unlock();
//        }
//        return result;
//    }
//    public void close(){
//        lock.lock();
//        try {
//            closed = true;
//            notEmpty.signalAll();
//        } finally {
//            lock.unlock();
//        }
//    }
//
//}
