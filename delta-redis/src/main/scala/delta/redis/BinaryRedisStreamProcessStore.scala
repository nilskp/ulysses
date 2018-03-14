package delta.redis

import _root_.redis.clients.jedis.BinaryJedis

import scala.collection.Map
import scala.collection.JavaConverters._
import scala.concurrent.{ ExecutionContext, Future }

import scuff.Serializer

/**
  * Snapshot-store using Redis
  * as backend.
  */
class BinaryRedisStreamProcessStore[K, T](
    keyCodec: Serializer[K],
    snapshotCodec: Serializer[delta.Snapshot[T]],
    hashName: String,
    blockingCtx: ExecutionContext)(
    jedisProvider: ((BinaryJedis => Any) => Any))
  extends BinaryRedisSnapshotStore(keyCodec, snapshotCodec, hashName, blockingCtx)(jedisProvider) {

  private def newJUHashMap(size: Int) = new java.util.HashMap[Array[Byte], Array[Byte]]((size / 0.67f).toInt, 0.67f)

  def readBatch(keys: Iterable[K]): Future[Map[K, Snapshot]] = Future {
    val binKeys = keys.iterator.map(keyCodec.encode).toSeq
    jedis(_.hmget(hash, binKeys: _*))
      .iterator.asScala
      .zip(keys.iterator)
      .foldLeft(Map.empty[K, Snapshot]) {
        case (map, (valBytes, key)) =>
          if (valBytes != null) {
            map.updated(key, snapshotCodec decode valBytes)
          } else map
      }
  }(blockingCtx)

  def writeBatch(snapshots: Map[K, Snapshot]): Future[Unit] = Future {
    val jmap = snapshots.iterator.foldLeft(newJUHashMap(snapshots.size)) {
      case (jmap, (key, snapshot)) =>
        jmap.put(keyCodec encode key, snapshotCodec encode snapshot)
        jmap
    }
    jedis(_.hmset(hash, jmap))
    ()
  }(blockingCtx)

  def refresh(key: K, revision: Int, tick: Long): Future[Unit] = Future {
    val binKey = keyCodec encode key
    jedis { jedis =>
      val snapshot = snapshotCodec decode jedis.hget(hash, binKey)
      val revised = snapshot.copy(revision = revision, tick = tick)
      jedis.hset(hash, binKey, snapshotCodec encode revised)
      ()
    }
  }(blockingCtx)

  def refreshBatch(revisions: Map[K, (Int, Long)]): Future[Unit] = Future {
    val revSeq = revisions.toSeq
    val binKeys = revSeq.map {
      case (key, _) => keyCodec encode key
    }
    jedis { jedis =>
      val refreshed = jedis.hmget(hash, binKeys: _*)
        .iterator.asScala
        .zip(revSeq.iterator)
        .foldLeft(newJUHashMap(revisions.size)) {
          case (jmap, (binVal, (key, (rev, tick)))) =>
            if (binVal != null) {
              val snapshot = snapshotCodec decode binVal
              val refreshed = snapshot.copy(revision = rev, tick = tick)
              jmap.put(keyCodec encode key, snapshotCodec encode refreshed)
            }
            jmap
        }
      jedis.hmset(hash, refreshed)
      ()
    }
  }(blockingCtx)

}
