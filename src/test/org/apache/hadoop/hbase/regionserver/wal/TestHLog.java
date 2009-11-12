/**
 * Copyright 2007 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.regionserver.wal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseTestCase;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.regionserver.wal.HLog;
import org.apache.hadoop.hbase.regionserver.wal.HLogKey;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.SequenceFile.Reader;


/** JUnit test case for HLog */
public class TestHLog extends HBaseTestCase implements HConstants {
  private Path dir;
  private MiniDFSCluster cluster;

  @Override
  public void setUp() throws Exception {
    // Enable append for these tests.
    this.conf.setBoolean("dfs.support.append", true);
    // Make block sizes small.
    this.conf.setInt("dfs.blocksize", 1024 * 1024);
    this.conf.setInt("hbase.regionserver.flushlogentries", 1);
    cluster = new MiniDFSCluster(conf, 3, true, (String[])null);
    // Set the hbase.rootdir to be the home directory in mini dfs.
    this.conf.set(HConstants.HBASE_DIR,
      this.cluster.getFileSystem().getHomeDirectory().toString());
    super.setUp();
    this.dir = new Path("/hbase", getName());
    if (fs.exists(dir)) {
      fs.delete(dir, true);
    }
  }

  @Override
  public void tearDown() throws Exception {
    if (this.fs.exists(this.dir)) {
      this.fs.delete(this.dir, true);
    }
    shutdownDfs(cluster);
    super.tearDown();
  }

  /**
   * Just write multiple logs then split.  Before fix for HADOOP-2283, this
   * would fail.
   * @throws IOException
   */
  public void testSplit() throws IOException {
    final byte [] tableName = Bytes.toBytes(getName());
    final byte [] rowName = tableName;
    HLog log = new HLog(this.fs, this.dir, this.conf, null);
    final int howmany = 3;
    // Add edits for three regions.
    try {
      for (int ii = 0; ii < howmany; ii++) {
        for (int i = 0; i < howmany; i++) {
          for (int j = 0; j < howmany; j++) {
            List<KeyValue> edit = new ArrayList<KeyValue>();
            byte [] family = Bytes.toBytes("column");
            byte [] qualifier = Bytes.toBytes(Integer.toString(j));
            byte [] column = Bytes.toBytes("column:" + Integer.toString(j));
            edit.add(new KeyValue(rowName, family, qualifier, 
                System.currentTimeMillis(), column));
            System.out.println("Region " + i + ": " + edit);
            log.append(Bytes.toBytes("" + i), tableName, edit,
              System.currentTimeMillis());
          }
        }
        log.rollWriter();
      }
      List<Path> splits =
        HLog.splitLog(this.testDir, this.dir, this.fs, this.conf);
      verifySplits(splits, howmany);
      log = null;
    } finally {
      if (log != null) {
        log.closeAndDelete();
      }
    }
  }

  /**
   * Test new HDFS-265 sync.
   * @throws Exception
   */
  public void testSync() throws Exception {
    byte [] bytes = Bytes.toBytes(getName());
    // First verify that using streams all works.
    Path p = new Path(this.dir, getName() + ".fsdos");
    FSDataOutputStream out = fs.create(p);
    out.write(bytes);
    out.sync();
    FSDataInputStream in = fs.open(p);
    assertTrue(in.available() > 0);
    byte [] buffer = new byte [1024];
    int read = in.read(buffer);
    assertEquals(bytes.length, read);
    out.close();
    in.close();
    Path subdir = new Path(this.dir, "hlogdir");
    HLog wal = new HLog(this.fs, subdir, this.conf, null);
    final int total = 20;
    for (int i = 0; i < total; i++) {
      List<KeyValue> kvs = new ArrayList<KeyValue>();
      kvs.add(new KeyValue(Bytes.toBytes(i), bytes, bytes));
      wal.append(bytes, bytes, kvs, System.currentTimeMillis());
    }
    // Now call sync and try reading.  Opening a Reader before you sync just
    // gives you EOFE.
    wal.sync();
    // Open a Reader.
    Path walPath = wal.computeFilename(wal.getFilenum());
    SequenceFile.Reader reader = HLog.getReader(this.fs, walPath, this.conf);
    int count = 0;
    HLogKey key = new HLogKey();
    while(reader.next(key)) count++;
    assertEquals(total, count);
    reader.close();
    // Add test that checks to see that an open of a Reader works on a file
    // that has had a sync done on it.
    for (int i = 0; i < total; i++) {
      List<KeyValue> kvs = new ArrayList<KeyValue>();
      kvs.add(new KeyValue(Bytes.toBytes(i), bytes, bytes));
      wal.append(bytes, bytes, kvs, System.currentTimeMillis());
    }
    reader = HLog.getReader(this.fs, walPath, this.conf);
    count = 0;
    while(reader.next(key)) count++;
    assertTrue(count >= total);
    reader.close();
    // If I sync, should see double the edits.
    wal.sync();
    reader = HLog.getReader(this.fs, walPath, this.conf);
    count = 0;
    while(reader.next(key)) count++;
    assertEquals(total * 2, count);
    // Now do a test that ensures stuff works when we go over block boundary,
    // especially that we return good length on file.
    final byte [] value = new byte[1025 * 1024];  // Make a 1M value.
    for (int i = 0; i < total; i++) {
      List<KeyValue> kvs = new ArrayList<KeyValue>();
      kvs.add(new KeyValue(Bytes.toBytes(i), bytes, value));
      wal.append(bytes, bytes, kvs, System.currentTimeMillis());
    }
    // Now I should have written out lots of blocks.  Sync then read.
    wal.sync();
    reader = HLog.getReader(this.fs, walPath, this.conf);
    count = 0;
    while(reader.next(key)) count++;
    assertEquals(total * 3, count);
    reader.close();
    // Close it and ensure that closed, Reader gets right length also.
    wal.close();
    reader = HLog.getReader(this.fs, walPath, this.conf);
    count = 0;
    while(reader.next(key)) count++;
    assertEquals(total * 3, count);
    reader.close();
  }
 
  private void verifySplits(List<Path> splits, final int howmany)
  throws IOException {
    assertEquals(howmany, splits.size());
    for (int i = 0; i < splits.size(); i++) {
      SequenceFile.Reader r = HLog.getReader(this.fs, splits.get(i), this.conf);
      try {
        HLogKey key = new HLogKey();
        KeyValue kv = new KeyValue();
        int count = 0;
        String previousRegion = null;
        long seqno = -1;
        while(r.next(key, kv)) {
          String region = Bytes.toString(key.getRegionName());
          // Assert that all edits are for same region.
          if (previousRegion != null) {
            assertEquals(previousRegion, region);
          }
          assertTrue(seqno < key.getLogSeqNum());
          seqno = key.getLogSeqNum();
          previousRegion = region;
          System.out.println(key + " " + kv);
          count++;
        }
        assertEquals(howmany * howmany, count);
      } finally {
        r.close();
      }
    }
  }

  /**
   * Tests that we can write out an edit, close, and then read it back in again.
   * @throws IOException
   */
  public void testEditAdd() throws IOException {
    final int COL_COUNT = 10;
    final byte [] regionName = Bytes.toBytes("regionname");
    final byte [] tableName = Bytes.toBytes("tablename");
    final byte [] row = Bytes.toBytes("row");
    Reader reader = null;
    HLog log = new HLog(fs, dir, this.conf, null);
    try {
      // Write columns named 1, 2, 3, etc. and then values of single byte
      // 1, 2, 3...
      long timestamp = System.currentTimeMillis();
      List<KeyValue> cols = new ArrayList<KeyValue>();
      for (int i = 0; i < COL_COUNT; i++) {
        cols.add(new KeyValue(row, Bytes.toBytes("column"), 
            Bytes.toBytes(Integer.toString(i)),
          timestamp, new byte[] { (byte)(i + '0') }));
      }
      log.append(regionName, tableName, cols, System.currentTimeMillis());
      long logSeqId = log.startCacheFlush();
      log.completeCacheFlush(regionName, tableName, logSeqId);
      log.close();
      Path filename = log.computeFilename(log.getFilenum());
      log = null;
      // Now open a reader on the log and assert append worked.
      reader = HLog.getReader(fs, filename, conf);
      HLogKey key = new HLogKey();
      KeyValue val = new KeyValue();
      for (int i = 0; i < COL_COUNT; i++) {
        reader.next(key, val);
        assertTrue(Bytes.equals(regionName, key.getRegionName()));
        assertTrue(Bytes.equals(tableName, key.getTablename()));
        assertTrue(Bytes.equals(row, val.getRow()));
        assertEquals((byte)(i + '0'), val.getValue()[0]);
        System.out.println(key + " " + val);
      }
      while (reader.next(key, val)) {
        // Assert only one more row... the meta flushed row.
        assertTrue(Bytes.equals(regionName, key.getRegionName()));
        assertTrue(Bytes.equals(tableName, key.getTablename()));
        assertTrue(Bytes.equals(HLog.METAROW, val.getRow()));
        assertTrue(Bytes.equals(HLog.METAFAMILY, val.getFamily()));
        assertEquals(0, Bytes.compareTo(HLog.COMPLETE_CACHE_FLUSH,
          val.getValue()));
        System.out.println(key + " " + val);
      }
    } finally {
      if (log != null) {
        log.closeAndDelete();
      }
      if (reader != null) {
        reader.close();
      }
    }
  }
}
