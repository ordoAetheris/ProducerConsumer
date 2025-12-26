package com.ordoAetheris.drafts.task;
/**
 Задача: Producer–Consumer: WorkQueue (unbounded) с close/cancel
 Спецификация
 Нужно реализовать WorkQueue<T>:
 Использовать только НЕ-МНОГОПОТОЧНЫЕ коллекции
 void put(T item)
 item != null, иначе IllegalArgumentException
 если очередь закрыта → IllegalStateException
 иначе кладёт item и будит ожидающих consumers (если есть)

 T take() throws InterruptedException
 если очередь пуста и НЕ закрыта → ждёт
 если очередь закрыта и пуста → возвращает null (это “EOF”)
 иначе возвращает элемент

 void close()
 переводит очередь в closed
 разблокирует всех, кто ждёт в take()
 повторный close() допускается (idempotent)

 Инварианты:
 ни одного lost item
 ни одного duplicate item
 никакого вечного ожидания после close()

 Реальные аналоги этого всего - Kafka, RabbitMQ, LinkedBlockingQueue,ArrayBlockingQueue
 // */
public class WorkQueue<T> {
    public void put(T t) {}
    public T take() throws InterruptedException {return null;}
    public void close() throws InterruptedException {};

}
