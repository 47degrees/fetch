/*
 * Copyright 2016-2018 47 Degrees, LLC. <http://www.47deg.com>
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
 */

package fetch

import org.scalatest.{FreeSpec, Matchers}

import cats._
import cats.implicits._
import cats.effect._
import cats.data.NonEmptyList
import cats.instances.list._
import cats.syntax.apply._

import fetch._
import fetch.implicits._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class FetchTests extends FreeSpec with Matchers {
  import TestHelper._

  implicit def executionContext: ExecutionContext = ExecutionContext.Implicits.global
  implicit val timer: Timer[IO] = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  // Data sources

  case class One(id: Int)
  implicit object OneSource extends DataSource[One, Int] {
    override def name = "OneSource"

    override def fetchOne[F[_] : ConcurrentEffect](id: One): F[Option[Int]] =
      ConcurrentEffect[F].delay(Option(id.id))

    override def fetchMany[F[_] : ConcurrentEffect](ids: NonEmptyList[One]): F[Map[One, Int]] =
      ConcurrentEffect[F].delay(ids.toList.map(one => (one, one.id)).toMap)
  }
  def one(id: Int): Fetch[Int] = Fetch(One(id))

  case class Many(n: Int)
  implicit object ManySource extends DataSource[Many, List[Int]] {
    override def name = "ManySource"
    override def fetchOne[F[_] : ConcurrentEffect](id: Many): F[Option[List[Int]]] =
      ConcurrentEffect[F].delay(Option(0 until id.n toList))

    override def fetchMany[F[_] : ConcurrentEffect](ids: NonEmptyList[Many]): F[Map[Many, List[Int]]] =
      ConcurrentEffect[F].delay(ids.toList.map(m => (m, 0 until m.n toList)).toMap)
  }
  def many(id: Int): Fetch[List[Int]] = Fetch(Many(id))

  case class AnotherOne(id: Int)
  implicit object AnotheroneSource extends DataSource[AnotherOne, Int] {
    override def name = "AnotherOneSource"
    override def fetchOne[F[_] : ConcurrentEffect](id: AnotherOne): F[Option[Int]] =
      ConcurrentEffect[F].delay(Option(id.id))
    override def fetchMany[F[_] : ConcurrentEffect](ids: NonEmptyList[AnotherOne]): F[Map[AnotherOne, Int]] =
      ConcurrentEffect[F].delay(ids.toList.map(anotherone => (anotherone, anotherone.id)).toMap)
  }
  def anotherOne(id: Int): Fetch[Int] = Fetch(AnotherOne(id))

  // Fetch ops

  "We can lift plain values to Fetch" in {
    val fetch: Fetch[Int] = Fetch.pure(42)
    Fetch.run(fetch).unsafeRunSync shouldEqual 42
  }

  "We can lift values which have a Data Source to Fetch" in {
    Fetch.run(one(1)).unsafeRunSync shouldEqual 1
  }

  "We can map over Fetch values" in {
    val fetch = one(1).map(_ + 1)
    Fetch.run(fetch).unsafeRunSync shouldEqual 2
  }

  "We can use fetch inside a for comprehension" in {
    val fetch = for {
      o <- one(1)
      t <- one(2)
    } yield (o, t)

    Fetch.run(fetch).unsafeRunSync shouldEqual (1, 2)
  }

  "We can mix data sources" in {
    val fetch: Fetch[(Int, List[Int])] = for {
      o <- one(1)
      m <- many(3)
    } yield (o, m)

    Fetch.run(fetch).unsafeRunSync shouldEqual (1, List(0, 1, 2))
  }

  "We can use Fetch as a cartesian" in {
    import cats.syntax.all._

    val fetch: Fetch[(Int, List[Int])] = (one(1), many(3)).tupled
    val io                             = Fetch.run(fetch)

    io.unsafeRunSync shouldEqual (1, List(0, 1, 2))
  }

  "We can use Fetch as an applicative" in {
    import cats.syntax.all._

    val fetch: Fetch[Int] = (one(1), one(2), one(3)).mapN(_ + _ + _)
    val io                = Fetch.run(fetch)

    io.unsafeRunSync shouldEqual 6
  }

  "We can traverse over a list with a Fetch for each element" in {
    import cats.instances.list._
    import cats.syntax.all._

    val fetch: Fetch[List[Int]] = for {
      manies <- many(3)
      ones   <- manies.traverse(one)
    } yield ones
    val io = Fetch.run(fetch)

    io.unsafeRunSync shouldEqual List(0, 1, 2)
  }

  "We can depend on previous computations of Fetch values" in {
    val fetch: Fetch[Int] = for {
      o <- one(1)
      t <- one(o + 1)
    } yield o + t

    val io = Fetch.run(fetch)

    io.unsafeRunSync shouldEqual 3
  }

  "We can collect a list of Fetch into one" in {
    import cats.instances.list._
    import cats.syntax.all._

    val sources: List[Fetch[Int]] = List(one(1), one(2), one(3))
    val fetch: Fetch[List[Int]]   = sources.sequence
    val io                        = Fetch.run(fetch)

    io.unsafeRunSync shouldEqual List(1, 2, 3)
  }

  "We can collect a list of Fetches with heterogeneous sources" in {
    import cats.instances.list._
    import cats.syntax.all._

    val sources: List[Fetch[Int]] = List(one(1), one(2), one(3), anotherOne(4), anotherOne(5))
    val fetch: Fetch[List[Int]]   = sources.sequence
    val io                        = Fetch.run(fetch)

    io.unsafeRunSync shouldEqual List(1, 2, 3, 4, 5)
  }

  "We can collect the results of a traversal" in {
    import cats.instances.list._
    import cats.syntax.all._

    val fetch = List(1, 2, 3).traverse(one)
    val io    = Fetch.run(fetch)

    io.unsafeRunSync shouldEqual List(1, 2, 3)
  }

  // Execution model

  "Monadic bind implies sequential execution" in {
    val fetch = for {
      o <- one(1)
      t <- one(2)
    } yield (o, t)

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync

    env.rounds.size shouldEqual 2
  }

  "Traversals are implicitly batched" in {
    import cats.instances.list._
    import cats.syntax.all._

    val fetch: Fetch[List[Int]] = for {
      manies <- many(3)
      ones   <- manies.traverse(one)
    } yield ones

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync

    env.rounds.size shouldEqual 2
  }

  "Identities are deduped when batched" in {
    import cats.instances.list._
    import cats.syntax.traverse._

    val manies = List(1, 1, 2)
    val fetch: Fetch[List[Int]] = for {
      ones <- manies.traverse(one)
    } yield ones

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync

    result shouldEqual manies
    env.rounds.size shouldEqual 1
    env.rounds.head.queries.size shouldEqual 1
    env.rounds.head.queries.head.request should matchPattern {
      case FetchMany(NonEmptyList(One(1), List(One(2))), _) =>
    }
  }

  "The product of two fetches implies parallel fetching" in {
    import cats.syntax.all._

    val fetch: Fetch[(Int, List[Int])] = (one(1) |@| many(3)).tupled

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync

    env.rounds.size shouldEqual 1
    env.rounds.head.queries.size shouldEqual 2
  }

  "Concurrent fetching calls batches only when it can" in {
    import cats.syntax.all._

    val fetch: Fetch[(Int, List[Int])] = (one(1) |@| many(3)).tupled

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync

    result shouldEqual (1, List(0, 1, 2))
    env.rounds.size shouldEqual 1
    totalBatches(env.rounds) shouldEqual 0
  }

  "Concurrent fetching performs requests to multiple data sources in parallel" in {
    import cats.syntax.all._

    val fetch: Fetch[((Int, List[Int]), Int)] = ((one(1) |@| many(2)).tupled |@| anotherOne(3)).tupled

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync

    result shouldEqual ((1, List(0, 1)), 3)
    env.rounds.size shouldEqual 1
    totalBatches(env.rounds) shouldEqual 0
  }

  "The product of concurrent fetches implies everything fetched concurrently" in {
    import cats.syntax.all._

    val fetch = (
      (
        one(1) |@|
        (one(2) |@| one(3)).tupled
      ).tupled
        |@|
        one(4)
    ).tupled

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync

    val stats = (env.rounds.size, totalBatches(env.rounds), totalFetched(env.rounds))
    stats shouldEqual (1, 1, 4)
  }

  "The product of concurrent fetches of the same type implies everything fetched in a single batch" in {
    val aFetch = for {
      a <- one(1)  // round 1
      b <- many(1) // round 2
      c <- one(1)  // round 3
    } yield c
    val anotherFetch = for {
      a <- one(2)  // round 1
      m <- many(2) // round 2
      c <- one(2)  // round 3
    } yield c

    val fetch = (
      (
        aFetch
          |@|
          anotherFetch
      ).tupled
        |@|
      one(3)       // round 1
    ).tupled

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync

    val stats = (env.rounds.size, totalBatches(env.rounds), totalFetched(env.rounds))
    stats shouldEqual (3, 3, 7)
  }

  "Every level of joined concurrent fetches is combined and batched" in {
    val aFetch = for {
      a <- one(1)  // round 1
      b <- many(1) // round 2
      c <- one(1)  // round 3
    } yield c
    val anotherFetch = for {
      a <- one(2)  // round 1
      m <- many(2) // round 2
      c <- one(2)  // round 3
    } yield c

    val fetch = (aFetch |@| anotherFetch).tupled

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync

    val stats = (env.rounds.size, totalBatches(env.rounds), totalFetched(env.rounds))
    stats shouldEqual (3, 3, 6)
  }

  "Every level of sequenced concurrent fetches is batched" in {
    import cats.instances.list._
    import cats.syntax.all._

    val aFetch =
      for {
        a <- List(2, 3, 4).traverse(one)   // round 1
        b <- List(0, 1).traverse(many)     // round 2
        c <- List(9, 10, 11).traverse(one) // round 3
      } yield c

    val anotherFetch =
      for {
        a <- List(5, 6, 7).traverse(one)    // round 1
        b <- List(2, 3).traverse(many)      // round 2
        c <- List(12, 13, 14).traverse(one) // round 3
      } yield c

    val fetch = (
       (
         aFetch
           |@|
         anotherFetch
      ).tupled
        |@|
        List(15, 16, 17).traverse(one)      // round 1
    ).tupled

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync

    env.rounds.size shouldEqual 3
    totalBatches(env.rounds) shouldEqual 3
    totalFetched(env.rounds) shouldEqual 9 + 4 + 6
  }

  "The product of two fetches from the same data source implies batching" in {
    import cats.syntax.all._

    val fetch: Fetch[(Int, Int)] = (one(1) |@| one(3)).tupled

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync

    env.rounds.size shouldEqual 1
    totalBatches(env.rounds) shouldEqual 1
    totalFetched(env.rounds) shouldEqual 2
  }

  "Sequenced fetches are run concurrently" in {
    import cats.instances.list._
    import cats.syntax.all._

    val sources: List[Fetch[Int]] = List(one(1), one(2), one(3), anotherOne(4), anotherOne(5))
    val fetch: Fetch[List[Int]]   = sources.sequence

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync

    env.rounds.size shouldEqual 1
    totalBatches(env.rounds) shouldEqual 2
  }

  "Sequenced fetches are deduped" in {
    import cats.instances.list._
    import cats.syntax.all._

    val sources: List[Fetch[Int]] = List(one(1), one(2), one(1))
    val fetch: Fetch[List[Int]]   = sources.sequence

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync

    env.rounds.size shouldEqual 1
    totalBatches(env.rounds) shouldEqual 1
    totalFetched(env.rounds) shouldEqual 2
  }

  "Traversals are batched" in {
    import cats.instances.list._
    import cats.syntax.traverse._

    val fetch = List(1, 2, 3).traverse(one)

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync

    env.rounds.size shouldEqual 1
    totalBatches(env.rounds) shouldEqual 1
  }

  "Duplicated sources are only fetched once" in {
    import cats.instances.list._
    import cats.syntax.traverse._

    val fetch = List(1, 2, 1).traverse(one)

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync

    env.rounds.size shouldEqual 1
    totalFetched(env.rounds) shouldEqual 2
  }

  "Sources that can be fetched concurrently inside a for comprehension will be" in {
    import cats.instances.list._
    import cats.syntax.traverse._

    val fetch = for {
      v      <- Fetch.pure(List(1, 2, 1))
      result <- v.traverse(one)
    } yield result

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync

    env.rounds.size shouldEqual 1
    totalFetched(env.rounds) shouldEqual 2
  }

  "Pure Fetches allow to explore further in the Fetch" in {
    val aFetch = for {
      a <- Fetch.pure(2)
      b <- one(3)
    } yield a + b

    val fetch: Fetch[(Int, Int)] = (
      one(1)
       |@|
      aFetch
    ).tupled

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync

    env.rounds.size shouldEqual 1
    totalFetched(env.rounds) shouldEqual 2
  }

  // Caching

  // case class MyCache(state: Map[Any, Any] = Map.empty[Any, Any]) extends DataSourceCache {
  //   override def get[A](k: DataSourceIdentity): Option[A] = state.get(k).asInstanceOf[Option[A]]
  //   override def update[A](k: DataSourceIdentity, v: A): MyCache =
  //     copy(state = state.updated(k, v))
  // }

  // val fullCache: MyCache = MyCache(
  //   Map(
  //     OneSource.identity(One(1))   -> 1,
  //     OneSource.identity(One(2))   -> 2,
  //     OneSource.identity(One(3))   -> 3,
  //     OneSource.identity(One(1))   -> 1,
  //     ManySource.identity(Many(2)) -> List(0, 1)
  //   )
  // )

    // "Elements are cached and thus not fetched more than once" in {
  //   val fetch = for {
  //     aOne       <- one(1)
  //     anotherOne <- one(1)
  //     _          <- one(1)
  //     _          <- one(2)
  //     _          <- one(3)
  //     _          <- one(1)
  //     _          <- Fetch.traverse(List(1, 2, 3))(one)
  //     _          <- one(1)
  //   } yield aOne + anotherOne

  //   Fetch
  //     .runEnv[Future](fetch)
  //     .map(env => {
  //       val rounds = env.rounds

  //       totalFetched(rounds) shouldEqual 3
  //     })
  // }

  // "Elements that are cached won't be fetched" in {
  //   val fetch = for {
  //     aOne       <- one(1)
  //     anotherOne <- one(1)
  //     _          <- one(1)
  //     _          <- one(2)
  //     _          <- one(3)
  //     _          <- one(1)
  //     _          <- Fetch.traverse(List(1, 2, 3))(one)
  //     _          <- one(1)
  //   } yield aOne + anotherOne

  //   val fut = Fetch.runEnv[Future](
  //     fetch,
  //     InMemoryCache(
  //       OneSource.identity(One(1)) -> 1,
  //       OneSource.identity(One(2)) -> 2,
  //       OneSource.identity(One(3)) -> 3
  //     )
  //   )

  //   fut.map(env => {
  //     val rounds = env.rounds

  //     rounds.size shouldEqual 0
  //   })
  // }

  // "We can use a custom cache" in {
  //   val fetch = for {
  //     aOne       <- one(1)
  //     anotherOne <- one(1)
  //     _          <- one(1)
  //     _          <- one(2)
  //     _          <- one(3)
  //     _          <- one(1)
  //     _          <- Fetch.traverse(List(1, 2, 3))(one)
  //     _          <- one(1)
  //   } yield aOne + anotherOne
  //   val fut = Fetch.runEnv[Future](
  //     fetch,
  //     InMemoryCache(
  //       OneSource.identity(One(1))   -> 1,
  //       OneSource.identity(One(2))   -> 2,
  //       OneSource.identity(One(3))   -> 3,
  //       ManySource.identity(Many(2)) -> List(0, 1)
  //     )
  //   )

  //   fut.map(env => {
  //     val rounds = env.rounds

  //     rounds.size shouldEqual 0
  //   })
  // }

  // case class ForgetfulCache() extends DataSourceCache {
  //   override def get[A](k: DataSourceIdentity): Option[A]               = None
  //   override def update[A](k: DataSourceIdentity, v: A): ForgetfulCache = this
  // }

  // "We can use a custom cache that discards elements" in {
  //   val fetch = for {
  //     aOne       <- one(1)
  //     anotherOne <- one(1)
  //     _          <- one(1)
  //     _          <- one(2)
  //     _          <- one(3)
  //     _          <- one(1)
  //     _          <- one(1)
  //   } yield aOne + anotherOne

  //   val fut = Fetch.runEnv[Future](fetch, ForgetfulCache())

  //   fut.map(env => {
  //     totalFetched(env.rounds) shouldEqual 7
  //   })
  // }

  // "We can use a custom cache that discards elements together with concurrent fetches" in {
  //   val fetch = for {
  //     aOne       <- one(1)
  //     anotherOne <- one(1)
  //     _          <- one(1)
  //     _          <- one(2)
  //     _          <- Fetch.traverse(List(1, 2, 3))(one)
  //     _          <- one(3)
  //     _          <- one(1)
  //     _          <- one(1)
  //   } yield aOne + anotherOne

  //   val fut = Fetch.runEnv[Future](fetch, ForgetfulCache())

  //   fut.map(env => {
  //     totalFetched(env.rounds) shouldEqual 10
  //   })
  // }

  // "We can fetch multiple items at the same time" in {
  //   val fetch: Fetch[List[Int]] = Fetch.multiple(One(1), One(2), One(3))
  //   Fetch.runFetch[Future](fetch).map {
  //     case (env, res) =>
  //       res shouldEqual List(1, 2, 3)
  //       totalFetched(env.rounds) shouldEqual 3
  //       totalBatches(env.rounds) shouldEqual 1
  //       env.rounds.size shouldEqual 1
  //   }
  // }

  // Errors

  // "Data sources with errors throw fetch failures" in {
  //   val fetch: Fetch[Int] = Fetch(Never())
  //   val io                = Fetch.run[IO](fetch)

  //   io.attempt
  //     .map(either =>
  //       either should matchPattern {
  //         case Left(NotFound(Never(), _)) =>
  //       })
  //   .unsafeRunSync
  // }

  // "Data sources with errors throw fetch failures that can be handled" in {
  //   val fetch: Fetch[Int] = Fetch(Never())
  //   val io                = Fetch.run[IO](fetch)

  //   io.handleErrorWith(err => IO(42))
  //     .unsafeRunSync shouldEqual 42
  // }

  // "Data sources with errors and cached values throw fetch failures with the cache" in {
  //   val fetch: Fetch[Int] = Fetch(Never())
  //   val cache = InMemoryCache(
  //     OneSource.identity(One(1)) -> 1
  //   )

  //   ME.attempt(Fetch.run[Future](fetch, cache)).map {
  //     case Left(NotFound(env, _)) => env.cache shouldEqual cache
  //     case _                      => fail("Cache should be populated")
  //   }
  // }

  // "Data sources with errors won't fail if they're cached" in {
  //   val fetch: Fetch[Int] = Fetch(Never())
  //   val cache = InMemoryCache(
  //     NeverSource.identity(Never()) -> 1
  //   )
  //   Fetch.run[Future](fetch, cache).map(_ shouldEqual 1)
  // }

  // "We can lift errors to Fetch" in {
  //   val fetch: Fetch[Int] = Fetch.error(DidNotFound())

  //   ME.attempt(Fetch.run[Future](fetch)).map {
  //     case Left(UnhandledException(_, DidNotFound())) => assert(true)
  //     case _                                          => fail("Should've thrown NotFound exception")
  //   }
  // }

  // "We can lift handle and recover from errors in Fetch" in {
  //   import cats.syntax.applicativeError._

  //   val fetch: Fetch[Int] = Fetch.error(DidNotFound())
  //   val fut               = Fetch.run[Future](fetch)
  //   ME.handleErrorWith(fut)(err => Future.successful(42)).map(_ shouldEqual 42)
  // }

  // "If a fetch fails in the left hand of a product the product will fail" in {
  //   val fetch: Fetch[(Int, List[Int])] = Fetch.join(Fetch.error(DidNotFound()), many(3))
  //   val fut                            = Fetch.run[Future](fetch)

  //   ME.attempt(Fetch.run[Future](fetch)).map {
  //     case Left(UnhandledException(_, DidNotFound())) => assert(true)
  //     case _                                          => fail("Should've thrown NotFound exception")
  //   }
  // }

  // "If a fetch fails in the right hand of a product the product will fail" in {
  //   val fetch: Fetch[(List[Int], Int)] = Fetch.join(many(3), Fetch.error(DidNotFound()))
  //   val fut                            = Fetch.run[Future](fetch)

  //   ME.attempt(Fetch.run[Future](fetch)).map {
  //     case Left(UnhandledException(_, DidNotFound())) => assert(true)
  //     case _                                          => fail("Should've thrown NotFound exception")
  //   }
  // }

  // "If there is a missing identity in the left hand of a product the product will fail" in {
  //   val fetch: Fetch[(Int, List[Int])] = Fetch.join(Fetch(Never()), many(3))
  //   val fut                            = Fetch.run[Future](fetch)

  //   ME.attempt(Fetch.run[Future](fetch)).map {
  //     case Left(MissingIdentities(_, missing)) =>
  //       missing shouldEqual Map(NeverSource.name -> List(Never()))
  //     case _ => fail("Should've thrown a fetch failure")
  //   }
  // }

  // "If there is a missing identity in the right hand of a product the product will fail" in {
  //   val fetch: Fetch[(List[Int], Int)] = Fetch.join(many(3), Fetch(Never()))
  //   val fut                            = Fetch.run[Future](fetch)

  //   ME.attempt(fut).map {
  //     case Left(MissingIdentities(_, missing)) =>
  //       missing shouldEqual Map(NeverSource.name -> List(Never()))
  //     case _ => fail("Should've thrown a fetch failure")
  //   }
  // }
}
