/*
 * Copyright 2011 the original author or authors.
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
package org.gradle.cache.internal

import org.gradle.cache.PersistentIndexedCacheParameters
import org.gradle.cache.internal.FileLockManager.LockMode
import org.gradle.cache.internal.btree.BTreePersistentIndexedCache
import org.gradle.internal.Factory
import org.gradle.internal.serialize.BaseSerializerFactory
import org.gradle.internal.serialize.Serializer
import org.gradle.test.fixtures.concurrent.ConcurrentSpec
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.junit.Rule
import spock.lang.Unroll

import static org.gradle.cache.internal.FileLockManager.LockMode.*
import static org.gradle.cache.internal.filelock.LockOptionsBuilder.mode

class DefaultCacheAccessTest extends ConcurrentSpec {
    private static final BaseSerializerFactory SERIALIZER_FACTORY = new BaseSerializerFactory()

    @Rule final TestNameTestDirectoryProvider tmpDir = new TestNameTestDirectoryProvider()
    final FileLockManager lockManager = Mock()
    final CacheInitializationAction initializationAction = Mock()
    final File lockFile = tmpDir.file('lock.bin')
    final File cacheDir = tmpDir.file('caches')
    final FileLock lock = Mock()
    final BTreePersistentIndexedCache<String, Integer> backingCache = Mock()

    private DefaultCacheAccess newAccess(LockMode lockMode) {
        new DefaultCacheAccess("<display-name>", lockFile, mode(lockMode), cacheDir, lockManager, initializationAction, executorFactory) {
            @Override
            def <K, V> BTreePersistentIndexedCache<K, V> doCreateCache(File cacheFile, Serializer<K> keySerializer, Serializer<V> valueSerializer) {
                return backingCache
            }
        }
    }

    def "acquires lock on open and releases on close when lock mode is shared"() {
        def access = newAccess(Shared)

        when:
        access.open()

        then:
        1 * lockManager.lock(lockFile, mode(Shared), "<display-name>") >> lock
        1 * initializationAction.requiresInitialization(lock) >> false
        _ * lock.state
        0 * _._

        when:
        access.close()

        then:
        _ * lock.state
        1 * lock.close()
        0 * _._
    }

    def "acquires lock on open and releases on close when lock mode is exclusive"() {
        def access = newAccess(Exclusive)

        when:
        access.open()

        then:
        1 * lockManager.lock(lockFile, mode(Exclusive), "<display-name>") >> lock
        1 * initializationAction.requiresInitialization(lock) >> false
        _ * lock.state
        0 * _._

        when:
        access.close()

        then:
        _ * lock.state
        1 * lock.close()
        0 * _._
    }

    def "initializes cache on open when lock mode is shared by upgrading lock"() {
        def exclusiveLock = Mock(FileLock)
        def sharedLock = Mock(FileLock)
        def access = newAccess(Shared)

        when:
        access.open()

        then:
        1 * lockManager.lock(lockFile, mode(Shared), "<display-name>") >> lock
        1 * initializationAction.requiresInitialization(lock) >> true
        1 * lock.close()

        then:
        1 * lockManager.lock(lockFile, mode(Exclusive), "<display-name>") >> exclusiveLock
        1 * initializationAction.requiresInitialization(exclusiveLock) >> true
        1 * exclusiveLock.writeFile(_) >> { Runnable r -> r.run() }
        1 * initializationAction.initialize(exclusiveLock)
        1 * exclusiveLock.close()

        then:
        1 * lockManager.lock(lockFile, mode(Shared), "<display-name>") >> sharedLock
        1 * initializationAction.requiresInitialization(sharedLock) >> false
        _ * sharedLock.state
        0 * _._
    }

    def "initializes cache on open when lock mode is exclusive"() {
        def access = newAccess(Exclusive)

        when:
        access.open()

        then:
        1 * lockManager.lock(lockFile, mode(Exclusive), "<display-name>") >> lock
        1 * initializationAction.requiresInitialization(lock) >> true
        1 * lock.writeFile(_) >> { Runnable r -> r.run() }
        1 * initializationAction.initialize(lock)
        _ * lock.state
        0 * _._
    }

    def "cleans up when cache validation fails"() {
        def failure = new RuntimeException()
        def access = newAccess(Exclusive)

        when:
        access.open()

        then:
        1 * lockManager.lock(lockFile, mode(Exclusive), "<display-name>") >> lock
        1 * initializationAction.requiresInitialization(lock) >> { throw failure }
        1 * lock.close()
        0 * _._

        and:
        RuntimeException e = thrown()
        e == failure
    }

    def "cleans up when initialization fails"() {
        def failure = new RuntimeException()
        def exclusiveLock = Mock(FileLock)
        def access = newAccess(Shared)

        when:
        access.open()

        then:
        1 * lockManager.lock(lockFile, mode(Shared), "<display-name>") >> lock
        1 * initializationAction.requiresInitialization(lock) >> true
        1 * lock.close()

        then:
        1 * lockManager.lock(lockFile, mode(Exclusive), "<display-name>") >> exclusiveLock
        1 * initializationAction.requiresInitialization(exclusiveLock) >> true
        1 * exclusiveLock.writeFile(_) >> { Runnable r -> r.run() }
        1 * initializationAction.initialize(exclusiveLock) >> { throw failure }
        1 * exclusiveLock.close()
        0 * _._

        and:
        RuntimeException e = thrown()
        e == failure
    }

    def "initializes cache on open when lock mode is none"() {
        def action = Mock(Runnable)
        def access = newAccess(None)

        def contentionAction

        when:
        access.open()

        then:
        0 * _._

        when:
        access.useCache("some action", action)

        then:
        1 * lockManager.lock(lockFile, mode(Exclusive), "<display-name>") >> lock
        1 * lockManager.allowContention(lock, _ as Runnable) >> { FileLock l, Runnable r -> contentionAction = r }
        1 * initializationAction.requiresInitialization(lock) >> true
        1 * lock.writeFile(_) >> { Runnable r -> r.run() }
        1 * initializationAction.initialize(lock)
        1 * action.run()
        _ * lock.mode >> Exclusive
        _ * lock.state
        0 * _._

        when:
        contentionAction.run()

        then:
        1 * lock.close()

        when:
        access.useCache("some action", action)

        then:
        1 * lockManager.lock(lockFile, mode(Exclusive), "<display-name>") >> lock
        1 * lockManager.allowContention(lock, _ as Runnable) >> { FileLock l, Runnable r -> contentionAction = r }
        1 * initializationAction.requiresInitialization(lock) >> true
        1 * lock.writeFile(_) >> { Runnable r -> r.run() }
        1 * initializationAction.initialize(lock)
        1 * action.run()
        _ * lock.mode >> Exclusive
        _ * lock.state
        0 * _._
    }

    def "does not acquire lock on open when initial lock mode is none"() {
        def access = newAccess(None)

        when:
        access.open()

        then:
        0 * _._

        when:
        access.close()

        then:
        0 * _._

        and:
        !access.owner
    }

    @Unroll
    def "cannot be opened more than once for mode #lockMode"() {
        lockManager.lock(lockFile, _, "<display-name>") >> lock
        def access = newAccess(lockMode)

        when:
        access.open()
        access.open()

        then:
        thrown(IllegalStateException)

        where:
        lockMode << [Shared, Exclusive, None]
    }

    def "using cache pushes an operation and acquires lock but does not release it at the end of the operation"() {
        Factory<String> action = Mock()
        def access = newAccess(None)

        when:
        access.open()
        access.useCache("some operation", action)

        then:
        1 * lockManager.lock(lockFile, mode(Exclusive), "<display-name>") >> lock
        1 * initializationAction.requiresInitialization(lock) >> false
        _ * lock.state
        1 * lockManager.allowContention(lock, _ as Runnable)

        then:
        1 * action.create() >> {
            assert access.owner == Thread.currentThread()
        }

        then:
        0 * _._

        and:
        !access.owner
    }

    def "nested use cache operation does not release the lock"() {
        Factory<String> action = Mock()
        def access = newAccess(None)

        when:
        access.open()
        access.useCache("some operation", action)

        then:
        1 * lockManager.lock(lockFile, mode(Exclusive), "<display-name>") >> lock
        1 * action.create() >> {
            access.useCache("nested operation") {
                assert access.owner == Thread.currentThread()
            }
        }

        then:
        !access.owner
    }

    def "use cache operation reuses existing file lock"() {
        Factory<String> action = Mock()
        def access = newAccess(None)

        when:
        access.open()
        access.useCache("some operation", action)

        then:
        1 * lockManager.lock(lockFile, mode(Exclusive), "<display-name>") >> lock
        1 * action.create() >> { assert access.owner == Thread.currentThread() }

        when:
        access.useCache("some other operation", action)

        then:
        0 * lockManager._
        1 * action.create() >> { assert access.owner == Thread.currentThread() }
        0 * _._

        and:
        !access.owner
    }

    def "use cache operation does not allow shared locks"() {
        def access = newAccess(Shared)

        given:
        1 * lockManager.lock(lockFile, mode(Shared), "<display-name>") >> lock
        access.open()

        when:
        access.useCache("some operation", Mock(Factory))

        then:
        thrown(UnsupportedOperationException)
    }

    def "long running operation pushes an operation and releases ownership but not lock"() {
        Factory<String> innerAction = Mock()
        Factory<String> outerAction = Mock()
        def access = newAccess(None)

        access.open()

        when:
        access.useCache("outer", outerAction)

        then:
        1 * lockManager.lock(lockFile, mode(Exclusive), "<display-name>") >> lock
        1 * lock.state

        and:
        outerAction.create() >> {
            assert access.owner == Thread.currentThread()
            access.longRunningOperation("some operation", innerAction)
            assert access.owner == Thread.currentThread()
            "result"
        }
        innerAction.create() >> {
            assert !access.owner
            "result"
        }

        and:
        0 * lock._
    }

    def "long running operation closes the lock if contended during action and reacquires on completion of action"() {
        Factory<String> action = Mock()
        def access = newAccess(None)

        access.open()

        when:
        access.useCache("outer") {
            access.longRunningOperation("some operation", action)
        }

        then:
        1 * lockManager.lock(lockFile, mode(Exclusive), "<display-name>") >> lock

        then:
        1 * action.create() >> {
            access.whenContended().run()
        }
        1 * lock.close()

        then:
        1 * lockManager.lock(lockFile, mode(Exclusive), "<display-name>") >> lock
    }

    def "long running operation closes the lock if contended before action and reacquires on completion of action"() {
        Factory<String> action = Mock()
        def access = newAccess(None)

        access.open()

        when:
        access.useCache("outer") {
            access.whenContended().run()
            access.longRunningOperation("some operation", action)
        }

        then:
        1 * lockManager.lock(lockFile, mode(Exclusive), "<display-name>") >> lock

        then:
        1 * lock.close()

        then:
        1 * action.create()

        then:
        1 * lockManager.lock(lockFile, mode(Exclusive), "<display-name>") >> lock
    }

    def "top-level long running operation does not lock file"() {
        Factory<String> action = Mock()
        def access = newAccess(None)

        when:
        access.open()
        access.longRunningOperation("some operation", action)

        then:
        1 * action.create() >> {
            assert !access.owner
        }

        then:
        0 * lock._
        0 * lockManager._
    }

    def "re-entrant long running operation does not lock file"() {
        Factory<String> action = Mock()
        def access = newAccess(None)

        when:
        access.open()
        access.longRunningOperation("some operation", action)

        then:
        1 * action.create() >> {
            access.longRunningOperation("other operation") {
                assert !access.owner
            }
        }

        then:
        0 * lock._
        0 * lockManager._
    }

    def "can create new cache"() {
        def access = newAccess(None)

        when:
        def cache = access.newCache(new PersistentIndexedCacheParameters('cache', String.class, Integer.class))

        then:
        cache instanceof MultiProcessSafePersistentIndexedCache
        0 * _._
    }

    def "contended action does nothing when no lock"() {
        def access = newAccess(None)
        access.open()

        when:
        access.whenContended().run()

        then:
        0 * _._
    }

    def "contended action safely closes the lock when cache is not busy"() {
        Factory<String> action = Mock()
        def access = newAccess(None)

        when:
        access.open()
        access.useCache("some operation", action)

        then:
        1 * lockManager.lock(lockFile, mode(Exclusive), "<display-name>") >> lock

        when:
        access.whenContended().run()

        then:
        1 * lock.close()
    }

    def "file access requires acquired lock"() {
        def runnable = Mock(Runnable)
        def access = newAccess(mode)

        given:
        lockManager.lock(lockFile, mode(Exclusive), "<display-name>") >> lock

        when:
        access.open()
        access.fileAccess.updateFile(runnable)

        then:
        thrown(IllegalStateException)

        where:
        mode << [Exclusive, None]
    }

    def "file access is available when there is an owner"() {
        def runnable = Mock(Runnable)
        def access = newAccess(mode)

        when:
        access.open()
        access.useCache("use cache", { access.fileAccess.updateFile(runnable)})

        then:
        1 * lockManager.lock(lockFile, mode(Exclusive), "<display-name>") >> lock
        1 * lock.updateFile(runnable)

        where:
        mode << [Exclusive, None]
    }

    def "file access can not be accessed when there is no owner"() {
        def runnable = Mock(Runnable)
        def access = newAccess(mode)

        given:
        lockManager.lock(lockFile, mode(Exclusive), "<display-name>") >> lock
        access.open()
        access.useCache("use cache", runnable)

        when:
        access.fileAccess.updateFile(runnable)

        then:
        thrown(IllegalStateException)

        where:
        mode << [Exclusive, None]
    }

    def "can close cache when the cache has not been used"() {
        def access = newAccess(None)

        when:
        access.open()
        access.close()

        then:
        0 * _
    }

    def "can close cache when there is no owner"() {
        def access = newAccess(None)

        given:
        lockManager.lock(lockFile, mode(Exclusive), "<display-name>") >> lock
        lock.writeFile(_) >> { Runnable r -> r.run() }
        access.open()
        def cache = access.newCache(new PersistentIndexedCacheParameters('cache', String.class, Integer.class))
        access.useCache("use cache", { cache.get("key") })

        when:
        access.close()

        then:
        1 * lock.close()
    }

    def "can close cache when the lock has been released"() {
        def access = newAccess(None)

        given:
        lockManager.lock(lockFile, mode(Exclusive), "<display-name>") >> lock
        lock.writeFile(_) >> { Runnable r -> r.run() }
        access.open()
        def cache = access.newCache(new PersistentIndexedCacheParameters('cache', String.class, Integer.class))
        access.useCache("use cache", { cache.get("key") })
        access.whenContended().run()
        lock.close()

        when:
        access.close()

        then:
        0 * lock._
    }

    def "releases lock acquired by cache decorator when contended"() {
        def decorator = Mock(CacheDecorator)
        def access = newAccess(None)

        given:
        CrossProcessCacheAccess cpAccess
        decorator.decorate(_, _, _, _, _) >> { String cacheId, String cacheName, MultiProcessSafePersistentIndexedCache persistentCache, CrossProcessCacheAccess crossProcessCacheAccess, AsyncCacheAccess asyncCacheAccess ->
            cpAccess = crossProcessCacheAccess
            persistentCache
        }

        access.open()

        when:
        def cache = access.newCache(new PersistentIndexedCacheParameters('cache', String.class, Integer.class).cacheDecorator(decorator))

        then:
        1 * lockManager.lock(lockFile, mode(Exclusive), "<display-name>") >> lock

        when:
        cpAccess.withFileLock {
            access.useCache("do something") {
                cache.get("something")
            }
            access.whenContended().run()
            "result"
        }

        then:
        1 * lock.close()

        cleanup:
        access?.close()
    }

    def "returns the same cache object when using same cache parameters"() {
        def access = newAccess(None)

        when:
        def cache1 = access.newCache(new PersistentIndexedCacheParameters('cache', String.class, Integer.class))
        def cache2 = access.newCache(new PersistentIndexedCacheParameters('cache', String.class, Integer.class))

        then:
        cache1 == cache2

        cleanup:
        access?.close()
    }

    def "throws InvalidCacheReuseException when cache value type differs"() {
        def access = newAccess(None)

        when:
        access.newCache(new PersistentIndexedCacheParameters('cache', String.class, Integer.class))
        access.newCache(new PersistentIndexedCacheParameters('cache', String.class, String.class))

        then:
        thrown(DefaultCacheAccess.InvalidCacheReuseException)

        cleanup:
        access?.close()
    }

    def "throws InvalidCacheReuseException when cache key type differs"() {
        def access = newAccess(None)

        when:
        access.newCache(new PersistentIndexedCacheParameters('cache', String.class, Integer.class))
        access.newCache(new PersistentIndexedCacheParameters('cache', Integer.class, Integer.class))

        then:
        thrown(DefaultCacheAccess.InvalidCacheReuseException)

        cleanup:
        access?.close()
    }

    def "throws InvalidCacheReuseException when cache decorator differs"() {
        def access = newAccess(None)
        def decorator = Mock(CacheDecorator)
        lockManager.lock(lockFile, mode(Exclusive), "<display-name>") >> lock
        decorator.decorate(_, _, _, _, _) >> { String cacheId, String cacheName, MultiProcessSafePersistentIndexedCache persistentCache, CrossProcessCacheAccess crossProcessCacheAccess, AsyncCacheAccess asyncCacheAccess ->
            persistentCache
        }

        when:
        access.newCache(new PersistentIndexedCacheParameters('cache', String.class, Integer.class))
        access.newCache(new PersistentIndexedCacheParameters('cache', String.class, Integer.class).cacheDecorator(decorator))

        then:
        thrown(DefaultCacheAccess.InvalidCacheReuseException)

        cleanup:
        access?.close()
    }

    def "returns the same cache object when cache decorator match"() {
        def access = newAccess(None)
        def decorator = Mock(CacheDecorator)
        lockManager.lock(lockFile, mode(Exclusive), "<display-name>") >> lock
        decorator.decorate(_, _, _, _, _) >> { String cacheId, String cacheName, MultiProcessSafePersistentIndexedCache persistentCache, CrossProcessCacheAccess crossProcessCacheAccess, AsyncCacheAccess asyncCacheAccess ->
            persistentCache
        }

        when:
        def cache1 = access.newCache(new PersistentIndexedCacheParameters('cache', String.class, Integer.class).cacheDecorator(decorator))
        def cache2 = access.newCache(new PersistentIndexedCacheParameters('cache', String.class, Integer.class).cacheDecorator(decorator))

        then:
        noExceptionThrown()
        cache1 == cache2

        cleanup:
        access?.close()
    }

    def "returns the same cache object when using compatible value serializer"() {
        def access = newAccess(None)

        when:
        def cache1 = access.newCache(new PersistentIndexedCacheParameters('cache', String.class, Integer.class))
        def cache2 = access.newCache(new PersistentIndexedCacheParameters('cache', String.class, SERIALIZER_FACTORY.getSerializerFor(Integer.class)))

        then:
        noExceptionThrown()
        cache1 == cache2

        cleanup:
        access?.close()
    }

    def "returns the same cache object when using compatible key serializer"() {
        def access = newAccess(None)

        when:
        def cache1 = access.newCache(new PersistentIndexedCacheParameters('cache', String.class, Integer.class))
        def cache2 = access.newCache(new PersistentIndexedCacheParameters('cache', SERIALIZER_FACTORY.getSerializerFor(String.class), SERIALIZER_FACTORY.getSerializerFor(Integer.class)))

        then:
        noExceptionThrown()
        cache1 == cache2

        cleanup:
        access?.close()
    }
}
