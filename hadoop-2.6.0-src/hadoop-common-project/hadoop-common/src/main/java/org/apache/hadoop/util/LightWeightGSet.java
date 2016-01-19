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
package org.apache.hadoop.util;

import java.io.PrintStream;
import java.util.ConcurrentModificationException;
import java.util.Iterator;

import org.apache.hadoop.HadoopIllegalArgumentException;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.util.StringUtils;

import com.google.common.annotations.VisibleForTesting;

/**
 * A low memory footprint {@link GSet} implementation,
 * which uses an array for storing the elements
 * and linked lists for collision resolution.
 *
 * No rehash will be performed.
 * Therefore, the internal array will never be resized.
 *
 * This class does not support null element.
 *
 * This class is not thread safe.
 *
 * @param <K> Key type for looking up the elements
 * @param <E> Element type, which must be
 *       (1) a subclass of K, and
 *       (2) implementing {@link LinkedElement} interface.
 */
@InterfaceAudience.Private
public class LightWeightGSet<K, E extends K> implements GSet<K, E> {
  /**
   * Elements of {@link LightWeightGSet}.
   */
  public static interface LinkedElement {
    /** Set the next element. */
    public void setNext(LinkedElement next);

    /** Get the next element. */
    public LinkedElement getNext();
  }

  static final int MAX_ARRAY_LENGTH = 1 << 30; //prevent int overflow problem 值:1073741824 size最大值
  static final int MIN_ARRAY_LENGTH = 1;//size最小值

  /**
   * An internal array of entries, which are the rows of the hash table.
   * The size must be a power of two.
   */
  private final LinkedElement[] entries;
  /** A mask for computing the array index from the hash value of an element. */
  private final int hash_mask;
  /** The size of the set (not the entry array).集合中包含的数量 */
  private int size = 0;
  /** Modification version for fail-fast.
   * @see ConcurrentModificationException
   * 被修改的次数，添加和修改、删除都会增加1
   */
  private int modification = 0;

  /**
   * @param recommended_length Recommended size of the internal array.
   */
  public LightWeightGSet(final int recommended_length) {
    final int actual = actualArrayLength(recommended_length);
    if (LOG.isDebugEnabled()) {
      LOG.debug("recommended=" + recommended_length + ", actual=" + actual);
    }
    entries = new LinkedElement[actual];
    hash_mask = entries.length - 1;
  }

  /**
   * compute actual length计算真实长度 
   * @param recommended 被推荐的参数
   */
  private static int actualArrayLength(int recommended) {
    if (recommended > MAX_ARRAY_LENGTH) {
      return MAX_ARRAY_LENGTH;
    } else if (recommended < MIN_ARRAY_LENGTH) {
      return MIN_ARRAY_LENGTH;
    } else {
      final int a = Integer.highestOneBit(recommended);
      return a == recommended? a: a << 1;
    }
  }

  @Override
  public int size() {
    return size;
  }

  /**
   * 根据key的hashCode定位到指定位置
   */
  private int getIndex(final K key) {
    return key.hashCode() & hash_mask;
  }

  /**
   * 将LinkedElement类型转化成E类型
   */
  private E convert(final LinkedElement e){
    @SuppressWarnings("unchecked")
    final E r = (E)e;
    return r;
  }

  @Override
  public E get(final K key) {
    //validate key
    if (key == null) {
      throw new NullPointerException("key == null");
    }

    //find element
    final int index = getIndex(key);
    for(LinkedElement e = entries[index]; e != null; e = e.getNext()) {
      if (e.equals(key)) {
        return convert(e);
      }
    }
    //element not found
    return null;
  }

  @Override
  public boolean contains(final K key) {
    return get(key) != null;
  }

  /**
   * 每次都放在队列头部
   * return 如果以前该值是存在的，则返回以前的该值
   */
  @Override
  public E put(final E element) {
    //validate element
    if (element == null) {
      throw new NullPointerException("Null element is not supported.");
    }
    if (!(element instanceof LinkedElement)) {
      throw new HadoopIllegalArgumentException(
          "!(element instanceof LinkedElement), element.getClass()="
          + element.getClass());
    }
    final LinkedElement e = (LinkedElement)element;

    //find index
    final int index = getIndex(element);

    //remove if it already exists
    final E existing = remove(index, element);

    //insert the element to the head of the linked list
    modification++;
    size++;
    e.setNext(entries[index]);
    entries[index] = e;

    return existing;
  }

  /**
   * Remove the element corresponding to the key,
   * given key.hashCode() == index.
   *
   * @return If such element exists, return it.
   *         Otherwise, return null.
   */
  private E remove(final int index, final K key) {
    if (entries[index] == null) {
      return null;
    } else if (entries[index].equals(key)) {//index位置的第一个就是要删除的元素
      //remove the head of the linked list
      modification++;
      size--;
      final LinkedElement e = entries[index];//该index位置的第一个元素内容,最后要被返回的
      entries[index] = e.getNext();//由于该index位置第一个元素要被删除，因此第二个设置到index位置
      e.setNext(null);//将e的下一个节点设置为null,因为e要被删除，我觉得这个位置计算不设置null也无所谓，不过可能有问题吧，属于垃圾回收问题
      return convert(e);
    } else {
      //head != null and key is not equal to head
      //search the element
      LinkedElement prev = entries[index];
      for(LinkedElement curr = prev.getNext(); curr != null; ) {
        if (curr.equals(key)) {
          //found the element, remove it
          modification++;
          size--;
          prev.setNext(curr.getNext());
          curr.setNext(null);//将当前的节点下一个设置为null,因为当前节点是要被删除的，并且要被返回
          return convert(curr);
        } else {
          prev = curr;
          curr = curr.getNext();
        }
      }
      //element not found
      return null;
    }
  }

  @Override
  public E remove(final K key) {
    //validate key
    if (key == null) {
      throw new NullPointerException("key == null");
    }
    return remove(getIndex(key), key);
  }

  @Override
  public Iterator<E> iterator() {
    return new SetIterator();
  }

  @Override
  public String toString() {
    final StringBuilder b = new StringBuilder(getClass().getSimpleName());
    b.append("(size=").append(size)
     .append(String.format(", %08x", hash_mask))
     .append(", modification=").append(modification)
     .append(", entries.length=").append(entries.length)
     .append(")");
    return b.toString();
  }

  /** Print detailed information of this object. */
  public void printDetails(final PrintStream out) {
    out.print(this + ", entries = [");
    for(int i = 0; i < entries.length; i++) {
      if (entries[i] != null) {
        LinkedElement e = entries[i];
        out.print("\n  " + i + ": " + e);
        for(e = e.getNext(); e != null; e = e.getNext()) {
          out.print(" -> " + e);
        }
      }
    }
    out.println("\n]");
  }

  /**
   * 遍历集合
   * 从entries开始遍历
   */
  public class SetIterator implements Iterator<E> {
    /** The starting modification for fail-fast. 当前遍历的时候,修改次数备份一下,用于比较是否在遍历期间,有修改操作*/
    private int iterModification = modification;
    /** The current index of the entry array. */
    private int index = -1;
    private LinkedElement cur = null;
    private LinkedElement next = nextNonemptyEntry();
    private boolean trackModification = true;//true表示如果遍历期间有对集合的修改,则不允许,抛异常

    /** Find the next nonempty entry starting at (index + 1). */
    private LinkedElement nextNonemptyEntry() {
      for(index++; index < entries.length && entries[index] == null; index++);
      return index < entries.length? entries[index]: null;
    }

    /**
     * 确定是否存在下一个元素
     */
    private void ensureNext() {
      if (trackModification && modification != iterModification) {//遍历期间有修改操作，则抛异常
        throw new ConcurrentModificationException("modification=" + modification
            + " != iterModification = " + iterModification);
      }
      if (next != null) {//存在没遍历完的entries
        return;
      }
      if (cur == null) {
        return;
      }
      next = cur.getNext();
      if (next == null) {
        next = nextNonemptyEntry();
      }
    }

    /**
     * 判断是否有下一个元素
     */
    @Override
    public boolean hasNext() {
      ensureNext();
      return next != null;
    }

    @Override
    public E next() {
      ensureNext();
      if (next == null) {
        throw new IllegalStateException("There are no more elements");
      }
      cur = next;
      next = null;
      return convert(cur);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void remove() {
      ensureNext();
      if (cur == null) {
        throw new IllegalStateException("There is no current element " +
            "to remove");
      }
      LightWeightGSet.this.remove((K)cur);//移除该集合的一个元素
      iterModification++;//因为是在迭代中移除的，因此该位置也会+1,防止因为修改次数+1导致的不能迭代
      cur = null;
    }

    public void setTrackModification(boolean trackModification) {
      this.trackModification = trackModification;
    }
  }
  
  /**
   * Let t = percentage of max memory.
   * Let e = round(log_2 t).
   * Then, we choose capacity = 2^e/(size of reference),
   * unless it is outside the close interval [1, 2^30].
   */
  public static int computeCapacity(double percentage, String mapName) {
    return computeCapacity(Runtime.getRuntime().maxMemory(), percentage,mapName);
  }
  
  @VisibleForTesting
  static int computeCapacity(long maxMemory, double percentage,
      String mapName) {
    if (percentage > 100.0 || percentage < 0.0) {
      throw new HadoopIllegalArgumentException("Percentage " + percentage
          + " must be greater than or equal to 0 "
          + " and less than or equal to 100");
    }
    if (maxMemory < 0) {
      throw new HadoopIllegalArgumentException("Memory " + maxMemory
          + " must be greater than or equal to 0");
    }
    if (percentage == 0.0 || maxMemory == 0) {
      return 0;
    }
    //VM detection
    //See http://java.sun.com/docs/hotspot/HotSpotFAQ.html#64bit_detection 
    final String vmBit = System.getProperty("sun.arch.data.model");//多少位操作系统

    //Percentage of max memory
    final double percentDivisor = 100.0/percentage;//一共100份，那么percentage占几份,
    final double percentMemory = maxMemory/percentDivisor;//该percentage百分比所应该占用的内存资源
    //即percentMemory = maxMemory / 100.0/percentage = maxMemory * percentage /100.0
    
    //compute capacity
    final int e1 = (int)(Math.log(percentMemory)/Math.log(2.0) + 0.5);
    final int e2 = e1 - ("32".equals(vmBit)? 2: 3);
    final int exponent = e2 < 0? 0: e2 > 30? 30: e2;
    final int c = 1 << exponent;

    LOG.info("Computing capacity for map " + mapName);
    LOG.info("VM type       = " + vmBit + "-bit");
    LOG.info(percentage + "% max memory "
        + StringUtils.TraditionalBinaryPrefix.long2String(maxMemory, "B", 1)
        + " = "
        + StringUtils.TraditionalBinaryPrefix.long2String((long) percentMemory,
            "B", 1));
    LOG.info("capacity      = 2^" + exponent + " = " + c + " entries");
    return c;
  }
  
  public void clear() {
    for (int i = 0; i < entries.length; i++) {
      entries[i] = null;
    }
    size = 0;
  }
}
