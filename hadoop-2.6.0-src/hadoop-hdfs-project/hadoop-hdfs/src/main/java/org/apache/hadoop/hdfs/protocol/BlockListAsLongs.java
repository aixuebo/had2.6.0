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
package org.apache.hadoop.hdfs.protocol;

import java.util.Iterator;
import java.util.List;
import java.util.Random;

import com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.ReplicaState;
import org.apache.hadoop.hdfs.server.datanode.ReplicaInfo;

/**
 * This class provides an interface for accessing list of blocks that
 * has been implemented as long[].
 * This class is useful for block report. Rather than send block reports
 * as a Block[] we can send it as a long[].
 *
 * The structure of the array is as follows:
 * 0: the length of the finalized replica list;
 * 1: the length of the under-construction replica list;
 * - followed by finalized replica list where each replica is represented by
 *   3 longs: one for the blockId, one for the block length, and one for
 *   the generation stamp;
 * - followed by the invalid replica represented with three -1s;
 * - followed by the under-construction replica list where each replica is
 *   represented by 4 longs: three for the block id, length, generation 
 *   stamp, and the fourth for the replica state.
 *   将block集合转换成long集合,每一个block转化成三个long,因为block=blockId+length+generation组成
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public class BlockListAsLongs implements Iterable<Block> {
  /**
   * A finalized block as 3 longs
   *   block-id and block length and generation stamp 3个long表示block
   */
  private static final int LONGS_PER_FINALIZED_BLOCK = 3;

  /**
   * An under-construction block as 4 longs
   *   block-id and block length, generation stamp and replica state
   *   4个long表示一个未构建完成的block
   */
  private static final int LONGS_PER_UC_BLOCK = 4;

  /** Number of longs in the header 头文件包含2个long,多少个Block、多少个ReplicaInfo*/
  private static final int HEADER_SIZE = 2;

  /**
   * Returns the index of the first long in blockList
   * belonging to the specified block.
   * The first long contains the block id.
   * 参数下标是从0开始计算
   */
  private int index2BlockId(int blockIndex) {
    if(blockIndex < 0 || blockIndex > getNumberOfBlocks())
      return -1;
    int finalizedSize = getNumberOfFinalizedReplicas();//block的个数
    if(blockIndex < finalizedSize)//说明该blockIndex是block
      return HEADER_SIZE + blockIndex * LONGS_PER_FINALIZED_BLOCK;//返回第blockIndex的block数据块第一个long在数组的下标
    return HEADER_SIZE + (finalizedSize + 1) * LONGS_PER_FINALIZED_BLOCK
            + (blockIndex - finalizedSize) * LONGS_PER_UC_BLOCK;//返回第blockIndex位置是Replicas的数据块
  }

  /**
   * 多少个Block、多少个ReplicaInfo
   * 每三个元素表示一个block
   * 三个元素表示
   * 每四个元素表示ReplicaInfo
   */
  private final long[] blockList;
  
  /**
   * Create block report from finalized and under construction lists of blocks.
   * 
   * @param finalized - list of finalized blocks
   * @param uc - list of under construction blocks
   */
  public BlockListAsLongs(final List<? extends Block> finalized,
                          final List<ReplicaInfo> uc) {
    int finalizedSize = finalized == null ? 0 : finalized.size();
    int ucSize = uc == null ? 0 : uc.size();
    //计算需要多少个long
    int len = HEADER_SIZE
              + (finalizedSize + 1) * LONGS_PER_FINALIZED_BLOCK
              + ucSize * LONGS_PER_UC_BLOCK;

    blockList = new long[len];

    // set the header
    blockList[0] = finalizedSize;
    blockList[1] = ucSize;

    // set finalized blocks
    for (int i = 0; i < finalizedSize; i++) {
      setBlock(i, finalized.get(i));
    }

    // set invalid delimiting block
    setDelimitingBlock(finalizedSize);

    // set under construction blocks
    for (int i = 0; i < ucSize; i++) {
      setBlock(finalizedSize + i, uc.get(i));
    }
  }

  public BlockListAsLongs() {
    this(null);
  }

  /**
   * Constructor
   * @param iBlockList - BlockListALongs create from this long[] parameter
   */
  public BlockListAsLongs(final long[] iBlockList) {
    if (iBlockList == null) {
      blockList = new long[HEADER_SIZE];
      return;
    }
    blockList = iBlockList;
  }

  public long[] getBlockListAsLongs() {
    return blockList;
  }

  /**
   * Iterates over blocks in the block report.
   * Avoids object allocation on each iteration.
   */
  @InterfaceAudience.Private
  @InterfaceStability.Evolving
  public class BlockReportIterator implements Iterator<Block> {
    private int currentBlockIndex;//第几个数据块
    private final Block block;//第几个数据块对象
    private ReplicaState currentReplicaState;//第几个数据块对应的状态ReplicaState

    BlockReportIterator() {
      this.currentBlockIndex = 0;
      this.block = new Block();
      this.currentReplicaState = null;
    }

    @Override
    public boolean hasNext() {
      return currentBlockIndex < getNumberOfBlocks();//currentBlockIndex < ReplicaInfo+block的个数之和
    }

    @Override
    public Block next() {
      block.set(blockId(currentBlockIndex),
                blockLength(currentBlockIndex),
                blockGenerationStamp(currentBlockIndex));
      currentReplicaState = blockReplicaState(currentBlockIndex);
      currentBlockIndex++;
      return block;
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException("Sorry. can't remove.");
    }

    /**
     * Get the state of the current replica.
     * The state corresponds to the replica returned
     * by the latest {@link #next()}. 
     */
    public ReplicaState getCurrentReplicaState() {
      return currentReplicaState;
    }
  }

  /**
   * Returns an iterator over blocks in the block report. 
   */
  @Override
  public Iterator<Block> iterator() {
    return getBlockReportIterator();
  }

  /**
   * Returns {@link BlockReportIterator}. 
   */
  public BlockReportIterator getBlockReportIterator() {
    return new BlockReportIterator();
  }

  /**
   * The number of blocks
   * @return - the number of blocks
   * ReplicaInfo+block的个数之和
   */
  public int getNumberOfBlocks() {
    assert blockList.length == HEADER_SIZE + 
            (blockList[0] + 1) * LONGS_PER_FINALIZED_BLOCK +
            blockList[1] * LONGS_PER_UC_BLOCK :
              "Number of blocks is inconcistent with the array length";
    return getNumberOfFinalizedReplicas() + getNumberOfUCReplicas();
  }

  /**
   * Returns the number of finalized replicas in the block report.
   * block的个数
   */
  private int getNumberOfFinalizedReplicas() {
    return (int)blockList[0];
  }

  /**
   * Returns the number of under construction replicas in the block report.
   * ReplicaInfo的个数
   */
  private int getNumberOfUCReplicas() {
    return (int)blockList[1];
  }

  /**
   * Returns the id of the specified replica of the block report.
   * 返回第几个block数据块的blockId
   * 参数是第几个block
   */
  private long blockId(int index) {
    return blockList[index2BlockId(index)];
  }

  /**
   * Returns the length of the specified replica of the block report.
   * 返回第几个block数据块的数据块长度
   * 参数是第几个block
   */
  private long blockLength(int index) {
    return blockList[index2BlockId(index) + 1];
  }

  /**
   * Returns the generation stamp of the specified replica of the block report.
   * 返回第几个block数据块的时间戳
   * 参数是第几个block
   */
  private long blockGenerationStamp(int index) {
    return blockList[index2BlockId(index) + 2];
  }

  /**
   * Returns the state of the specified replica of the block report.
   * 返回第几个block数据块的ReplicaState状态对象
   * 参数是第几个block
   */
  private ReplicaState blockReplicaState(int index) {
    if(index < getNumberOfFinalizedReplicas())
      return ReplicaState.FINALIZED;
    return ReplicaState.getState((int)blockList[index2BlockId(index) + 3]);
  }

  /**
   * Corrupt the generation stamp of the block with the given index.
   * Not meant to be used outside of tests.
   */
  @VisibleForTesting
  public long corruptBlockGSForTesting(final int blockIndex, Random rand) {
    long oldGS = blockList[index2BlockId(blockIndex) + 2];//返回第几个block数据块的时间戳
    while (blockList[index2BlockId(blockIndex) + 2] == oldGS) {
      blockList[index2BlockId(blockIndex) + 2] = rand.nextInt();
    }
    return oldGS;
  }

  /**
   * Corrupt the length of the block with the given index by truncation.
   * Not meant to be used outside of tests.
   */
  @VisibleForTesting
  public long corruptBlockLengthForTesting(final int blockIndex, Random rand) {
    long oldLength = blockList[index2BlockId(blockIndex) + 1];
    blockList[index2BlockId(blockIndex) + 1] =
        rand.nextInt((int) oldLength - 1);
    return oldLength;
  }
  
  /**
   * Set the indexTh block
   * @param index - the index of the block to set
   * @param b - the block is set to the value of the this block
   */
  private <T extends Block> void setBlock(final int index, final T b) {
    int pos = index2BlockId(index);
    blockList[pos] = b.getBlockId();
    blockList[pos + 1] = b.getNumBytes();
    blockList[pos + 2] = b.getGenerationStamp();
    if(index < getNumberOfFinalizedReplicas())
      return;
    //接下来说明是Replica对象,则需要多存储一个long
    assert ((ReplicaInfo)b).getState() != ReplicaState.FINALIZED :
      "Must be under-construction replica.";
    blockList[pos + 3] = ((ReplicaInfo)b).getState().getValue();
  }

  /**
   * Set the invalid delimiting block between the finalized and
   * the under-construction lists.
   * The invalid block has all three fields set to -1.
   * @param finalizedSzie - the size of the finalized list
   */
  private void setDelimitingBlock(final int finalizedSzie) {
    int idx = HEADER_SIZE + finalizedSzie * LONGS_PER_FINALIZED_BLOCK;
    blockList[idx] = -1;
    blockList[idx+1] = -1;
    blockList[idx+2] = -1;
  }

  /**
   * 获取集合中最大的一个时间戳
   */
  public long getMaxGsInBlockList() {
    long maxGs = -1;
    Iterator<Block> iter = getBlockReportIterator();
    while (iter.hasNext()) {
      Block b = iter.next();
      if (b.getGenerationStamp() > maxGs) {
        maxGs = b.getGenerationStamp();
      }
    }
    return maxGs;
  }
}
