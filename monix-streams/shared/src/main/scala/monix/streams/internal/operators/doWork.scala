/*
 * Copyright (c) 2014-2016 by its authors. Some rights reserved.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package monix.streams.internal.operators

import monix.execution.Cancelable
import monix.streams.{Observer, Observable, Ack}
import monix.streams.Ack.Cancel
import monix.streams.internal._
import monix.streams.Observer
import scala.concurrent.Future
import scala.util.control.NonFatal


private[monix] object doWork {
  /**
    * Implementation for [[Observable.doWork]].
    */
  def onNext[T](source: Observable[T])(cb: T => Unit): Observable[T] =
    Observable.unsafeCreate[T] { subscriber =>
      import subscriber.{scheduler => s}

      source.unsafeSubscribeFn(new Observer[T] {
        def onError(ex: Throwable) = subscriber.onError(ex)
        def onComplete() = subscriber.onComplete()

        def onNext(elem: T) = {
          // See Section 6.4. in the Rx Design Guidelines:
          // Protect calls to user code from within an operator
          var streamError = true
          try {
            cb(elem)
            streamError = false
            subscriber.onNext(elem)
          }
          catch {
            case NonFatal(ex) =>
              if (streamError) { subscriber.onError(ex); Cancel } else Future.failed(ex)
          }
        }
      })
    }

  /**
    * Implementation for [[Observable.doOnComplete]].
    */
  def onComplete[T](source: Observable[T])(cb: => Unit): Observable[T] =
    Observable.unsafeCreate[T] { subscriber =>
      import subscriber.{scheduler => s}

      source.unsafeSubscribeFn(new Observer[T] {
        def onNext(elem: T) = {
          subscriber.onNext(elem)
        }

        def onError(ex: Throwable): Unit = {
          subscriber.onError(ex)
        }

        def onComplete(): Unit = {
          // protecting call to user level code
          var streamError = true
          try {
            cb
            streamError = false
            subscriber.onComplete()
          }
          catch {
            case NonFatal(ex) =>
              subscriber.onError(ex)
          }
        }
      })
    }

  /**
    * Implementation for [[Observable.doOnError]].
    */
  def onError[T](source: Observable[T])(cb: Throwable => Unit): Observable[T] =
    Observable.unsafeCreate[T] { subscriber =>
      import subscriber.{scheduler => s}

      source.unsafeSubscribeFn(new Observer[T] {
        def onNext(elem: T) = {
          subscriber.onNext(elem)
        }

        def onError(ex: Throwable): Unit = {
          // in case our callback throws an error
          // the behavior is undefined, so we just
          // log it
          try {
            cb(ex)
          }
          catch {
            case NonFatal(err) =>
              s.reportFailure(err)
          }
          finally {
            subscriber.onError(ex)
          }
        }

        def onComplete(): Unit = {
          subscriber.onComplete()
        }
      })
    }

  /**
    * Implementation for [[Observable.doOnCanceled]].
    */
  def onCanceled[T](source: Observable[T])(cb: => Unit): Observable[T] =
    Observable.unsafeCreate[T] { subscriber =>
      import subscriber.{scheduler => s}
      val isActive = Cancelable(cb)

      source.unsafeSubscribeFn(new Observer[T] {
        def onNext(elem: T) = {
          subscriber.onNext(elem)
            .ifCanceledDoCancel(isActive)
        }

        def onError(ex: Throwable): Unit = {
          subscriber.onError(ex)
        }

        def onComplete(): Unit = {
          subscriber.onComplete()
        }
      })
    }

  /**
    * Implementation for [[Observable.doOnStart]].
    */
  def onStart[T](source: Observable[T])(cb: T => Unit): Observable[T] =
    Observable.unsafeCreate { subscriber =>
      import subscriber.{scheduler => s}

      source.unsafeSubscribeFn(new Observer[T] {
        private[this] var isStarted = false

        def onNext(elem: T) = {
          if (!isStarted) {
            isStarted = true
            var streamError = true
            try {
              cb(elem)
              streamError = false
              subscriber.onNext(elem)
            }
            catch {
              case NonFatal(ex) =>
                subscriber.onError(ex)
                Cancel
            }
          }
          else
            subscriber.onNext(elem)
        }

        def onError(ex: Throwable) = subscriber.onError(ex)
        def onComplete() = subscriber.onComplete()
      })
    }
}
