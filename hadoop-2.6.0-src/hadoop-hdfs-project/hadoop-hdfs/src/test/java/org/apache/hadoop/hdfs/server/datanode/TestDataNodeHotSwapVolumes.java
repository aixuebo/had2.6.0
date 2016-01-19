/**
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

package org.apache.hadoop.hdfs.server.datanode;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.ReconfigurationException;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.BlockMissingException;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.MiniDFSNNTopology;
import org.apache.hadoop.hdfs.protocol.BlockListAsLongs;
import org.apache.hadoop.hdfs.server.common.Storage;
import org.apache.hadoop.hdfs.server.protocol.DatanodeStorage;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.test.GenericTestUtils;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TestDataNodeHotSwapVolumes {
  private static final Log LOG = LogFactory.getLog(
    TestDataNodeHotSwapVolumes.class);
  private static final int BLOCK_SIZE = 512;
  private MiniDFSCluster cluster;

  @After
  public void tearDown() {
    shutdown();
  }

  private void startDFSCluster(int numNameNodes, int numDataNodes)
      throws IOException {
    shutdown();
    Configuration conf = new Configuration();
    conf.setLong(DFSConfigKeys.DFS_BLOCK_SIZE_KEY, BLOCK_SIZE);

    /*
     * Lower the DN heartbeat, DF rate, and recheck interval to one second
     * so state about failures and datanode death propagates faster.
     */
    conf.setInt(DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_KEY, 1);
    conf.setInt(DFSConfigKeys.DFS_DF_INTERVAL_KEY, 1000);
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_HEARTBEAT_RECHECK_INTERVAL_KEY,
        1000);

    MiniDFSNNTopology nnTopology =
        MiniDFSNNTopology.simpleFederatedTopology(numNameNodes);

    cluster = new MiniDFSCluster.Builder(conf)
        .nnTopology(nnTopology)
        .numDataNodes(numDataNodes)
        .build();
    cluster.waitActive();
  }

  private void shutdown() {
    if (cluster != null) {
      cluster.shutdown();
      cluster = null;
    }
  }

  private void createFile(Path path, int numBlocks)
      throws IOException, InterruptedException, TimeoutException {
    final short replicateFactor = 1;
    createFile(path, numBlocks, replicateFactor);
  }

  private void createFile(Path path, int numBlocks, short replicateFactor)
      throws IOException, InterruptedException, TimeoutException {
    createFile(0, path, numBlocks, replicateFactor);
  }

  private void createFile(int fsIdx, Path path, int numBlocks)
      throws IOException, InterruptedException, TimeoutException {
    final short replicateFactor = 1;
    createFile(fsIdx, path, numBlocks, replicateFactor);
  }

  private void createFile(int fsIdx, Path path, int numBlocks,
      short replicateFactor)
      throws IOException, TimeoutException, InterruptedException {
    final int seed = 0;
    final DistributedFileSystem fs = cluster.getFileSystem(fsIdx);
    DFSTestUtil.createFile(fs, path, BLOCK_SIZE * numBlocks,
        replicateFactor, seed);
    DFSTestUtil.waitReplication(fs, path, replicateFactor);
  }

  /**
   * Verify whether a file has enough content.
   */
  private static void verifyFileLength(FileSystem fs, Path path, int numBlocks)
      throws IOException {
    FileStatus status = fs.getFileStatus(path);
    assertEquals(numBlocks * BLOCK_SIZE, status.getLen());
  }

  /** Return the number of replicas for a given block in the file. */
  private static int getNumReplicas(FileSystem fs, Path file,
      int blockIdx) throws IOException {
    BlockLocation locs[] = fs.getFileBlockLocations(file, 0, Long.MAX_VALUE);
    return locs.length < blockIdx + 1 ? 0 : locs[blockIdx].getNames().length;
  }

  /**
   * Wait the block to have the exact number of replicas as expected.
   */
  private static void waitReplication(FileSystem fs, Path file, int blockIdx,
      int numReplicas)
      throws IOException, TimeoutException, InterruptedException {
    int attempts = 50;  // Wait 5 seconds.
    while (attempts > 0) {
      if (getNumReplicas(fs, file, blockIdx) == numReplicas) {
        return;
      }
      Thread.sleep(100);
      attempts--;
    }
    throw new TimeoutException("Timed out waiting the " + blockIdx + "-th block"
        + " of " + file + " to have " + numReplicas + " replicas.");
  }

  /** Parses data dirs from DataNode's configuration. */
  private static Collection<String> getDataDirs(DataNode datanode) {
    return datanode.getConf().getTrimmedStringCollection(
        DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY);
  }

  @Test
  public void testParseChangedVolumes() throws IOException {
    startDFSCluster(1, 1);
    DataNode dn = cluster.getDataNodes().get(0);
    Configuration conf = dn.getConf();

    String oldPaths = conf.get(DFS_DATANODE_DATA_DIR_KEY);
    List<StorageLocation> oldLocations = new ArrayList<StorageLocation>();
    for (String path : oldPaths.split(",")) {
      oldLocations.add(StorageLocation.parse(path));
    }
    assertFalse(oldLocations.isEmpty());

    String newPaths = "/foo/path1,/foo/path2";
    conf.set(DFS_DATANODE_DATA_DIR_KEY, newPaths);

    DataNode.ChangedVolumes changedVolumes =dn.parseChangedVolumes();
    List<StorageLocation> newVolumes = changedVolumes.newLocations;
    assertEquals(2, newVolumes.size());
    assertEquals(new File("/foo/path1").getAbsolutePath(),
      newVolumes.get(0).getFile().getAbsolutePath());
    assertEquals(new File("/foo/path2").getAbsolutePath(),
      newVolumes.get(1).getFile().getAbsolutePath());

    List<StorageLocation> removedVolumes = changedVolumes.deactivateLocations;
    assertEquals(oldLocations.size(), removedVolumes.size());
    for (int i = 0; i < removedVolumes.size(); i++) {
      assertEquals(oldLocations.get(i).getFile(),
          removedVolumes.get(i).getFile());
    }
  }

  @Test
  public void testParseChangedVolumesFailures() throws IOException {
    startDFSCluster(1, 1);
    DataNode dn = cluster.getDataNodes().get(0);
    Configuration conf = dn.getConf();
    try {
      conf.set(DFS_DATANODE_DATA_DIR_KEY, "");
      dn.parseChangedVolumes();
      fail("Should throw IOException: empty inputs.");
    } catch (IOException e) {
      GenericTestUtils.assertExceptionContains("No directory is specified.", e);
    }
  }

  /** Add volumes to the first DataNode. */
  private void addVolumes(int numNewVolumes) throws ReconfigurationException {
    File dataDir = new File(cluster.getDataDirectory());
    DataNode dn = cluster.getDataNodes().get(0);  // First DataNode.
    Configuration conf = dn.getConf();
    String oldDataDir = conf.get(DFS_DATANODE_DATA_DIR_KEY);

    List<File> newVolumeDirs = new ArrayList<File>();
    StringBuilder newDataDirBuf = new StringBuilder(oldDataDir);
    int startIdx = oldDataDir.split(",").length + 1;
    // Find the first available (non-taken) directory name for data volume.
    while (true) {
      File volumeDir = new File(dataDir, "data" + startIdx);
      if (!volumeDir.exists()) {
        break;
      }
      startIdx++;
    }
    for (int i = startIdx; i < startIdx + numNewVolumes; i++) {
      File volumeDir = new File(dataDir, "data" + String.valueOf(i));
      newVolumeDirs.add(volumeDir);
      volumeDir.mkdirs();
      newDataDirBuf.append(",");
      newDataDirBuf.append(volumeDir.toURI());
    }

    String newDataDir = newDataDirBuf.toString();
    dn.reconfigurePropertyImpl(DFS_DATANODE_DATA_DIR_KEY, newDataDir);
    assertEquals(newDataDir, conf.get(DFS_DATANODE_DATA_DIR_KEY));

    // Check that all newly created volumes are appropriately formatted.
    for (File volumeDir : newVolumeDirs) {
      File curDir = new File(volumeDir, "current");
      assertTrue(curDir.exists());
      assertTrue(curDir.isDirectory());
    }
  }

  private List<List<Integer>> getNumBlocksReport(int namesystemIdx) {
    List<List<Integer>> results = new ArrayList<List<Integer>>();
    final String bpid = cluster.getNamesystem(namesystemIdx).getBlockPoolId();
    List<Map<DatanodeStorage, BlockListAsLongs>> blockReports =
        cluster.getAllBlockReports(bpid);
    for (Map<DatanodeStorage, BlockListAsLongs> datanodeReport : blockReports) {
      List<Integer> numBlocksPerDN = new ArrayList<Integer>();
      for (BlockListAsLongs blocks : datanodeReport.values()) {
        numBlocksPerDN.add(blocks.getNumberOfBlocks());
      }
      results.add(numBlocksPerDN);
    }
    return results;
  }

  /**
   * Test adding one volume on a running MiniDFSCluster with only one NameNode.
   */
  @Test
  public void testAddOneNewVolume()
      throws IOException, ReconfigurationException,
      InterruptedException, TimeoutException {
    startDFSCluster(1, 1);
    String bpid = cluster.getNamesystem().getBlockPoolId();
    final int numBlocks = 10;

    addVolumes(1);

    Path testFile = new Path("/test");
    createFile(testFile, numBlocks);

    List<Map<DatanodeStorage, BlockListAsLongs>> blockReports =
        cluster.getAllBlockReports(bpid);
    assertEquals(1, blockReports.size());  // 1 DataNode
    assertEquals(3, blockReports.get(0).size());  // 3 volumes

    // FSVolumeList uses Round-Robin block chooser by default. Thus the new
    // blocks should be evenly located in all volumes.
    int minNumBlocks = Integer.MAX_VALUE;
    int maxNumBlocks = Integer.MIN_VALUE;
    for (BlockListAsLongs blockList : blockReports.get(0).values()) {
      minNumBlocks = Math.min(minNumBlocks, blockList.getNumberOfBlocks());
      maxNumBlocks = Math.max(maxNumBlocks, blockList.getNumberOfBlocks());
    }
    assertTrue(Math.abs(maxNumBlocks - maxNumBlocks) <= 1);
    verifyFileLength(cluster.getFileSystem(), testFile, numBlocks);
  }

  @Test(timeout = 60000)
  public void testAddVolumesDuringWrite()
      throws IOException, InterruptedException, TimeoutException,
      ReconfigurationException {
    startDFSCluster(1, 1);
    String bpid = cluster.getNamesystem().getBlockPoolId();
    Path testFile = new Path("/test");
    createFile(testFile, 4);  // Each volume has 2 blocks.

    addVolumes(2);

    // Continue to write the same file, thus the new volumes will have blocks.
    DFSTestUtil.appendFile(cluster.getFileSystem(), testFile, BLOCK_SIZE * 8);
    verifyFileLength(cluster.getFileSystem(), testFile, 8 + 4);
    // After appending data, there should be [2, 2, 4, 4] blocks in each volume
    // respectively.
    List<Integer> expectedNumBlocks = Arrays.asList(2, 2, 4, 4);

    List<Map<DatanodeStorage, BlockListAsLongs>> blockReports =
        cluster.getAllBlockReports(bpid);
    assertEquals(1, blockReports.size());  // 1 DataNode
    assertEquals(4, blockReports.get(0).size());  // 4 volumes
    Map<DatanodeStorage, BlockListAsLongs> dnReport =
        blockReports.get(0);
    List<Integer> actualNumBlocks = new ArrayList<Integer>();
    for (BlockListAsLongs blockList : dnReport.values()) {
      actualNumBlocks.add(blockList.getNumberOfBlocks());
    }
    Collections.sort(actualNumBlocks);
    assertEquals(expectedNumBlocks, actualNumBlocks);
  }

  @Test
  public void testAddVolumesToFederationNN()
      throws IOException, TimeoutException, InterruptedException,
      ReconfigurationException {
    // Starts a Cluster with 2 NameNode and 3 DataNodes. Each DataNode has 2
    // volumes.
    final int numNameNodes = 2;
    final int numDataNodes = 1;
    startDFSCluster(numNameNodes, numDataNodes);
    Path testFile = new Path("/test");
    // Create a file on the first namespace with 4 blocks.
    createFile(0, testFile, 4);
    // Create a file on the second namespace with 4 blocks.
    createFile(1, testFile, 4);

    // Add 2 volumes to the first DataNode.
    final int numNewVolumes = 2;
    addVolumes(numNewVolumes);

    // Append to the file on the first namespace.
    DFSTestUtil.appendFile(cluster.getFileSystem(0), testFile, BLOCK_SIZE * 8);

    List<List<Integer>> actualNumBlocks = getNumBlocksReport(0);
    assertEquals(cluster.getDataNodes().size(), actualNumBlocks.size());
    List<Integer> blocksOnFirstDN = actualNumBlocks.get(0);
    Collections.sort(blocksOnFirstDN);
    assertEquals(Arrays.asList(2, 2, 4, 4), blocksOnFirstDN);

    // Verify the second namespace also has the new volumes and they are empty.
    actualNumBlocks = getNumBlocksReport(1);
    assertEquals(4, actualNumBlocks.get(0).size());
    assertEquals(numNewVolumes,
        Collections.frequency(actualNumBlocks.get(0), 0));
  }

  @Test
  public void testRemoveOneVolume()
      throws ReconfigurationException, InterruptedException, TimeoutException,
      IOException {
    startDFSCluster(1, 1);
    final short replFactor = 1;
    Path testFile = new Path("/test");
    createFile(testFile, 10, replFactor);

    DataNode dn = cluster.getDataNodes().get(0);
    Collection<String> oldDirs = getDataDirs(dn);
    String newDirs = oldDirs.iterator().next();  // Keep the first volume.
    dn.reconfigurePropertyImpl(
        DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY, newDirs);
    assertFileLocksReleased(
      new ArrayList<String>(oldDirs).subList(1, oldDirs.size()));
    dn.scheduleAllBlockReport(0);

    try {
      DFSTestUtil.readFile(cluster.getFileSystem(), testFile);
      fail("Expect to throw BlockMissingException.");
    } catch (BlockMissingException e) {
      GenericTestUtils.assertExceptionContains("Could not obtain block", e);
    }

    Path newFile = new Path("/newFile");
    createFile(newFile, 6);

    String bpid = cluster.getNamesystem().getBlockPoolId();
    List<Map<DatanodeStorage, BlockListAsLongs>> blockReports =
        cluster.getAllBlockReports(bpid);
    assertEquals((int)replFactor, blockReports.size());

    BlockListAsLongs blocksForVolume1 =
        blockReports.get(0).values().iterator().next();
    // The first volume has half of the testFile and full of newFile.
    assertEquals(10 / 2 + 6, blocksForVolume1.getNumberOfBlocks());
  }

  @Test
  public void testReplicatingAfterRemoveVolume()
      throws InterruptedException, TimeoutException, IOException,
      ReconfigurationException {
    startDFSCluster(1, 2);
    final DistributedFileSystem fs = cluster.getFileSystem();
    final short replFactor = 2;
    Path testFile = new Path("/test");
    createFile(testFile, 4, replFactor);

    DataNode dn = cluster.getDataNodes().get(0);
    Collection<String> oldDirs = getDataDirs(dn);
    String newDirs = oldDirs.iterator().next();  // Keep the first volume.
    dn.reconfigurePropertyImpl(
        DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY, newDirs);
    assertFileLocksReleased(
      new ArrayList<String>(oldDirs).subList(1, oldDirs.size()));

    // Force DataNode to report missing blocks.
    dn.scheduleAllBlockReport(0);
    DataNodeTestUtils.triggerDeletionReport(dn);

    // The 2nd block only has 1 replica due to the removed data volume.
    waitReplication(fs, testFile, 1, 1);

    // Wait NameNode to replica missing blocks.
    DFSTestUtil.waitReplication(fs, testFile, replFactor);
  }

  /**
   * Asserts that the storage lock file in each given directory has been
   * released.  This method works by trying to acquire the lock file itself.  If
   * locking fails here, then the main code must have failed to release it.
   *
   * @param dirs every storage directory to check
   * @throws IOException if there is an unexpected I/O error
   */
  private static void assertFileLocksReleased(Collection<String> dirs)
      throws IOException {
    for (String dir: dirs) {
      StorageLocation sl = StorageLocation.parse(dir);
      File lockFile = new File(sl.getFile(), Storage.STORAGE_FILE_LOCK);
      RandomAccessFile raf = null;
      FileChannel channel = null;
      FileLock lock = null;
      try {
        raf = new RandomAccessFile(lockFile, "rws");
        channel = raf.getChannel();
        lock = channel.tryLock();
        assertNotNull(String.format(
          "Lock file at %s appears to be held by a different process.",
          lockFile.getAbsolutePath()), lock);
      } catch (OverlappingFileLockException e) {
        fail(String.format("Must release lock file at %s.",
          lockFile.getAbsolutePath()));
      } finally {
        if (lock != null) {
          try {
            lock.release();
          } catch (IOException e) {
            LOG.warn(String.format("I/O error releasing file lock %s.",
              lockFile.getAbsolutePath()), e);
          }
        }
        IOUtils.cleanup(null, channel, raf);
      }
    }
  }
}
