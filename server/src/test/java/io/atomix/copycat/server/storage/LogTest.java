/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.copycat.server.storage;

import io.atomix.catalyst.serializer.Serializer;
import io.atomix.catalyst.serializer.ServiceLoaderTypeResolver;
import org.testng.annotations.Test;

import java.io.File;

import static org.testng.Assert.*;

/**
 * Log test.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
@Test
public abstract class LogTest extends AbstractLogTest {

  /**
   * Creates a new log.
   */
  @Override
  protected Log createLog() {
    return createLog(String.format("target/test-logs/%s", logId));
  }

  /**
   * Creates a new log.
   */
  protected Log createLog(String directory) {
    return tempStorageBuilder()
        .withDirectory(new File(String.format("target/test-logs/%s", directory)))
        .withMaxSegmentSize(1024 * 1024)
        .withMaxEntriesPerSegment(1024)
        .withStorageLevel(storageLevel())
        .withSerializer(new Serializer(new ServiceLoaderTypeResolver()))
        .build()
        .open("copycat");
  }

  /**
   * Returns the log storage level.
   */
  protected abstract StorageLevel storageLevel();

  /**
   * Tests writing and reading an entry.
   */
  public void testCreateReadFirstEntry() {
    assertTrue(log.isEmpty());
    assertEquals(log.length(), 0);

    long index;
    try (TestEntry entry = log.create(TestEntry.class)) {
      entry.setTerm(1);
      index = log.append(entry);
    }

    assertEquals(log.length(), 1);
    assertFalse(log.isEmpty());

    try (TestEntry entry = log.get(index)) {
      assertEquals(entry.getTerm(), 1);
    }
  }

  /**
   * Tests creating and reading the last entry in the log.
   */
  public void testCreateReadLastEntry() {
    appendEntries(log, 100);
    assertEquals(log.length(), 100);

    long index;
    try (TestEntry entry = log.create(TestEntry.class)) {
      entry.setTerm(1);
      index = log.append(entry);
    }

    assertEquals(log.length(), 101);

    try (TestEntry entry = log.get(index)) {
      assertEquals(entry.getTerm(), 1);
    }
  }

  /**
   * Tests creating and reading the last entry in the log.
   */
  public void testCreateReadMiddleEntry() {
    appendEntries(log, 100);
    assertEquals(log.length(), 100);

    long index;
    try (TestEntry entry = log.create(TestEntry.class)) {
      entry.setTerm(1);
      index = log.append(entry);
    }

    appendEntries(log, 100);
    assertEquals(log.length(), 201);

    try (TestEntry entry = log.get(index)) {
      assertEquals(entry.getTerm(), 1);
    }
  }

  /**
   * Tests creating and reading entries after a roll over.
   */
  public void testCreateReadAfterRollOver() {
    appendEntries(log, 1100);

    long index;
    try (TestEntry entry = log.create(TestEntry.class)) {
      entry.setTerm(1);
      index = log.append(entry);
    }

    appendEntries(log, 1050);

    try (TestEntry entry = log.get(index)) {
      assertEquals(entry.getTerm(), 1);
    }
  }

  /**
   * Tests truncating entries in the log.
   */
  public void testTruncate() throws Throwable {
    appendEntries(log, 100);
    assertEquals(log.lastIndex(), 100);
    log.truncate(10);
    assertEquals(log.lastIndex(), 10);
    appendEntries(log, 10);
    assertEquals(log.lastIndex(), 20);
  }

  /**
   * Tests truncating and then appending entries in the log.
   */
  public void testTruncateAppend() throws Throwable {
    appendEntries(log, 10);
    assertEquals(log.lastIndex(), 10);
    TestEntry entry = log.create(TestEntry.class)
      .setIndex(10)
      .setTerm(2);
    log.truncate(entry.getIndex() - 1).append(entry);
    TestEntry result89 = log.get(9);
    assertEquals(result89.getTerm(), 1);
    TestEntry result90 = log.get(10);
    assertEquals(result90.getTerm(), 2);
  }

  /**
   * Tests truncating and then appending entries in the log.
   */
  public void testTruncateUncommitted() throws Throwable {
    appendEntries(log, 10);
    log.commit(1);
    assertEquals(log.lastIndex(), 10);
    TestEntry entry = log.create(TestEntry.class)
      .setIndex(10)
      .setTerm(2);
    log.truncate(entry.getIndex() - 1).append(entry);
    TestEntry result89 = log.get(9);
    assertEquals(result89.getTerm(), 1);
    TestEntry result90 = log.get(10);
    assertEquals(result90.getTerm(), 2);
  }

  /**
   * Tests truncating to a skipped index.
   */
  public void testTruncateSkipped() throws Throwable {
    appendEntries(log, 100);
    assertEquals(log.lastIndex(), 100);
    log.skip(10);
    appendEntries(log, 100);
    assertEquals(log.lastIndex(), 210);
    log.truncate(105);
    assertEquals(log.lastIndex(), 105);
    assertNull(log.commit(105).get(105));
  }

  /**
   * Tests emptying the log.
   */
  public void testTruncateZero() throws Throwable {
    appendEntries(log, 100);
    assertEquals(log.lastIndex(), 100);
    log.truncate(0);
    assertEquals(log.lastIndex(), 0);
    appendEntries(log, 10);
    assertEquals(log.lastIndex(), 10);
  }

  /**
   * Tests skipping entries in the log.
   */
  public void testSkip() throws Throwable {
    appendEntries(log, 100);

    log.skip(10);

    long index;
    try (TestEntry entry = log.create(TestEntry.class)) {
      entry.setTerm(1);
      index = log.append(entry);
    }

    assertEquals(log.length(), 111);

    log.commit(111);
    try (TestEntry entry = log.get(101)) {
      assertNull(entry);
    }

    try (TestEntry entry = log.get(index)) {
      assertEquals(entry.getTerm(), 1);
    }
  }

  /**
   * Tests skipping entries on a segment rollover.
   */
  public void testSkipOnRollOver() {
    appendEntries(log, 1020);

    log.skip(10);

    assertEquals(log.length(), 1030);

    long index;
    try (TestEntry entry = log.create(TestEntry.class)) {
      entry.setTerm(1);
      index = log.append(entry);
    }

    assertEquals(log.length(), 1031);

    try (TestEntry entry = log.commit(1031).get(1021)) {
      assertNull(entry);
    }

    try (TestEntry entry = log.get(index)) {
      assertEquals(entry.getTerm(), 1);
    }
  }

  /**
   * Appends a set of entries to the log.
   */
  protected void appendEntries(Log log, int entries) {
    for (int i = 0; i < entries; i++) {
      try (TestEntry entry = log.create(TestEntry.class)) {
        entry.setTerm(1);
        log.append(entry);
      }
    }
  }
}
