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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;

/**
 * A class that provides a line reader from an input stream.
 * Depending on the constructor used, lines will either be terminated by:
 * <ul>
 * <li>one of the following: '\n' (LF) , '\r' (CR),
 * or '\r\n' (CR+LF).</li>
 * <li><em>or</em>, a custom byte sequence delimiter</li>
 * </ul>
 * In both cases, EOF also terminates an otherwise unterminated
 * line.
 * 从输入流中读取一行信息
 */
@InterfaceAudience.LimitedPrivate({"MapReduce"})
@InterfaceStability.Unstable
public class LineReader implements Closeable {
  private static final int DEFAULT_BUFFER_SIZE = 64 * 1024;
  private int bufferSize = DEFAULT_BUFFER_SIZE;//缓冲字节
  private byte[] buffer;//缓冲字节数组
  // the number of bytes of real data in the buffer 缓冲字节数组中真实的字节数量
  private int bufferLength = 0;
  // the current position in the buffer 当前已经处理到缓冲字节的位置
  private int bufferPosn = 0;

  private InputStream in;//原始输入源
  
  private static final byte CR = '\r';
  private static final byte LF = '\n';

  // The line delimiter 每行的结束字符,如果该值为null,则使用默认为CRLF结尾分割每行数据
  private final byte[] recordDelimiterBytes;

  /**
   * Create a line reader that reads from the given stream using the
   * default buffer-size (64k).
   * @param in The input stream
   * @throws IOException
   */
  public LineReader(InputStream in) {
    this(in, DEFAULT_BUFFER_SIZE);
  }

  /**
   * Create a line reader that reads from the given stream using the 
   * given buffer-size.
   * @param in The input stream
   * @param bufferSize Size of the read buffer
   * @throws IOException
   */
  public LineReader(InputStream in, int bufferSize) {
    this.in = in;
    this.bufferSize = bufferSize;
    this.buffer = new byte[this.bufferSize];
    this.recordDelimiterBytes = null;
  }

  /**
   * Create a line reader that reads from the given stream using the
   * <code>io.file.buffer.size</code> specified in the given
   * <code>Configuration</code>.
   * @param in input stream
   * @param conf configuration
   * @throws IOException
   */
  public LineReader(InputStream in, Configuration conf) throws IOException {
    this(in, conf.getInt("io.file.buffer.size", DEFAULT_BUFFER_SIZE));
  }

  /**
   * Create a line reader that reads from the given stream using the
   * default buffer-size, and using a custom delimiter of array of
   * bytes.
   * @param in The input stream
   * @param recordDelimiterBytes The delimiter
   */
  public LineReader(InputStream in, byte[] recordDelimiterBytes) {
    this.in = in;
    this.bufferSize = DEFAULT_BUFFER_SIZE;
    this.buffer = new byte[this.bufferSize];
    this.recordDelimiterBytes = recordDelimiterBytes;
  }

  /**
   * Create a line reader that reads from the given stream using the
   * given buffer-size, and using a custom delimiter of array of
   * bytes.
   * @param in The input stream
   * @param bufferSize Size of the read buffer
   * @param recordDelimiterBytes The delimiter
   * @throws IOException
   */
  public LineReader(InputStream in, int bufferSize,
      byte[] recordDelimiterBytes) {
    this.in = in;
    this.bufferSize = bufferSize;
    this.buffer = new byte[this.bufferSize];
    this.recordDelimiterBytes = recordDelimiterBytes;
  }

  /**
   * Create a line reader that reads from the given stream using the
   * <code>io.file.buffer.size</code> specified in the given
   * <code>Configuration</code>, and using a custom delimiter of array of
   * bytes.
   * @param in input stream
   * @param conf configuration
   * @param recordDelimiterBytes The delimiter
   * @throws IOException
   */
  public LineReader(InputStream in, Configuration conf,
      byte[] recordDelimiterBytes) throws IOException {
    this.in = in;
    this.bufferSize = conf.getInt("io.file.buffer.size", DEFAULT_BUFFER_SIZE);
    this.buffer = new byte[this.bufferSize];
    this.recordDelimiterBytes = recordDelimiterBytes;
  }


  /**
   * Close the underlying stream.
   * @throws IOException
   */
  public void close() throws IOException {
    in.close();
  }
  
  /**
   * Read one line from the InputStream into the given Text.
   *
   * @param str the object to store the given line (without newline)
   * @param maxLineLength the maximum number of bytes to store into str;
   *  the rest of the line is silently discarded.
   * @param maxBytesToConsume the maximum number of bytes to consume
   *  in this call.  This is only a hint, because if the line cross
   *  this threshold, we allow it to happen.  It can overshoot
   *  potentially by as much as one buffer length.
   *
   * @return the number of bytes read including the (longest) newline
   * found.
   *
   * @throws IOException if the underlying stream throws
   */
  public int readLine(Text str, int maxLineLength,
                      int maxBytesToConsume) throws IOException {
    if (this.recordDelimiterBytes != null) {//使用自定义的结束符
      return readCustomLine(str, maxLineLength, maxBytesToConsume);
    } else {//使用默认的结束符
      return readDefaultLine(str, maxLineLength, maxBytesToConsume);
    }
  }

  protected int fillBuffer(InputStream in, byte[] buffer, boolean inDelimiter)
      throws IOException {
    return in.read(buffer);
  }

  /**
   * Read a line terminated by one of CR, LF, or CRLF.
   * 遇到CR, LF之一 或者CRCLF则推出
   * Text str 最终值要追加到str中
   * int maxLineLength 每行处理的最多字节,如果每行字节数超过了该值,则从该值--CRLF之间的内容忽略删除,即截断一部分字符串,返回截断后的数据给value
   * int maxBytesToConsume 到该字节位置,不再读取数据,一般是end-start
   * 返回被处理的字节数
   */
  private int readDefaultLine(Text str, int maxLineLength, int maxBytesToConsume)
  throws IOException {
    /* We're reading data from in, but the head of the stream may be
     * already buffered in buffer, so we have several cases:
     * 我们从输入流中读取数据,到缓冲池中,可能输入流的一部分数据已经在缓冲池里面了,因此有以下case用例
     * 1. No newline characters are in the buffer, so we need to copy
     *    everything and read another buffer from the stream.
     *    缓冲中没有新的字符串时候,要先读取数据到缓冲池中
     * 2. An unambiguously terminated line is in buffer, so we just
     *    copy to str.
     *    没有发现行终止符号的时候,将全部缓冲内容添加到str中,然后继续读取1,让数据进入缓冲层
     * 3. Ambiguously terminated line is in buffer, i.e. buffer ends
     *    in CR.  In this case we copy everything up to CR to str, but
     *    we also need to see what follows CR: if it's LF, then we
     *    need consume LF as well, so next call to readLine will read
     *    from after that.
     *    发现行终止符号后,数据解析结束
     * We use a flag prevCharCR to signal if previous character was CR
     * and, if it happens to be at the end of the buffer, delay
     * consuming it until we have a chance to look at the char that
     * follows.
     */
    str.clear();//清空原始数据
    int txtLength = 0; //tracks str.getLength(), as an optimization value已经读取了多少长度字节
    int newlineLength = 0; //length of terminating newline
    boolean prevCharCR = false; //true of prev char was CR
    long bytesConsumed = 0;//被处理的字节数
    do {
      int startPosn = bufferPosn; //starting from where we left off the last time 上一次读取后,在缓冲池中哪位置了
      if (bufferPosn >= bufferLength) {//已经处理的位置 大于 了字节数组的位置,因此重新加载数据
        startPosn = bufferPosn = 0;
        if (prevCharCR) {
          ++bytesConsumed; //account for CR from previous read
        }
        bufferLength = fillBuffer(in, buffer, prevCharCR);
        if (bufferLength <= 0) {
          break; // EOF
        }
      }
      for (; bufferPosn < bufferLength; ++bufferPosn) { //search for newline 找到缓冲区中换行字符为止
        if (buffer[bufferPosn] == LF) {
          newlineLength = (prevCharCR) ? 2 : 1;//查看如果有cr了,因此是2个字节
          ++bufferPosn; // at next invocation proceed from following byte
          break;
        }
        if (prevCharCR) { //CR + notLF, we are at notLF
          newlineLength = 1;
          break;
        }
        prevCharCR = (buffer[bufferPosn] == CR);
      }
      int readLength = bufferPosn - startPosn;//本次读取的总长度
      if (prevCharCR && newlineLength == 0) {
        --readLength; //CR at the end of the buffer 刨出最后一个分割换行字符
      }

      bytesConsumed += readLength;//本次在缓冲池中查找换行符为止已经读取了多少字节
      int appendLength = readLength - newlineLength;//本次追加的字符
      if (appendLength > maxLineLength - txtLength) {//截断数据,因为已经到到了maxLineLength的字节上限位置了
        appendLength = maxLineLength - txtLength;
      }
      if (appendLength > 0) {//小于0的,说明数据超过了最大行,因此被忽略,即截断后的数据不会再次被抓取回来
        str.append(buffer, startPosn, appendLength);//将内容追加到结果中,因为可能尚未发现换行符号,因此继续读取数据
        txtLength += appendLength;
      }
    } while (newlineLength == 0 && bytesConsumed < maxBytesToConsume);//只要没看到换行字符,并且还可以继续读取数据,则就不断读取数据

    if (bytesConsumed > Integer.MAX_VALUE) {
      throw new IOException("Too many bytes before newline: " + bytesConsumed);
    }
    return (int)bytesConsumed;
  }

  /**
   * Read a line terminated by a custom delimiter.
   */
  private int readCustomLine(Text str, int maxLineLength, int maxBytesToConsume)
      throws IOException {
   /* We're reading data from inputStream, but the head of the stream may be
    *  already captured in the previous buffer, so we have several cases:
    * 
    * 1. The buffer tail does not contain any character sequence which
    *    matches with the head of delimiter. We count it as a 
    *    ambiguous byte count = 0
    *    
    * 2. The buffer tail contains a X number of characters,
    *    that forms a sequence, which matches with the
    *    head of delimiter. We count ambiguous byte count = X
    *    
    *    // ***  eg: A segment of input file is as follows
    *    
    *    " record 1792: I found this bug very interesting and
    *     I have completely read about it. record 1793: This bug
    *     can be solved easily record 1794: This ." 
    *    
    *    delimiter = "record";
    *        
    *    supposing:- String at the end of buffer =
    *    "I found this bug very interesting and I have completely re"
    *    There for next buffer = "ad about it. record 179       ...."           
    *     
    *     The matching characters in the input
    *     buffer tail and delimiter head = "re" 
    *     Therefore, ambiguous byte count = 2 ****   //
    *     
    *     2.1 If the following bytes are the remaining characters of
    *         the delimiter, then we have to capture only up to the starting 
    *         position of delimiter. That means, we need not include the 
    *         ambiguous characters in str.
    *     
    *     2.2 If the following bytes are not the remaining characters of
    *         the delimiter ( as mentioned in the example ), 
    *         then we have to include the ambiguous characters in str. 
    */
    str.clear();
    int txtLength = 0; // tracks str.getLength(), as an optimization
    long bytesConsumed = 0;
    int delPosn = 0;
    int ambiguousByteCount=0; // To capture the ambiguous characters count
    do {
      int startPosn = bufferPosn; // Start from previous end position
      if (bufferPosn >= bufferLength) {
        startPosn = bufferPosn = 0;
        bufferLength = fillBuffer(in, buffer, ambiguousByteCount > 0);
        if (bufferLength <= 0) {
          str.append(recordDelimiterBytes, 0, ambiguousByteCount);
          break; // EOF
        }
      }
      for (; bufferPosn < bufferLength; ++bufferPosn) {
        if (buffer[bufferPosn] == recordDelimiterBytes[delPosn]) {
          delPosn++;
          if (delPosn >= recordDelimiterBytes.length) {
            bufferPosn++;
            break;
          }
        } else if (delPosn != 0) {
          bufferPosn--;
          delPosn = 0;
        }
      }
      int readLength = bufferPosn - startPosn;
      bytesConsumed += readLength;
      int appendLength = readLength - delPosn;
      if (appendLength > maxLineLength - txtLength) {
        appendLength = maxLineLength - txtLength;
      }
      if (appendLength > 0) {
        if (ambiguousByteCount > 0) {
          str.append(recordDelimiterBytes, 0, ambiguousByteCount);
          //appending the ambiguous characters (refer case 2.2)
          bytesConsumed += ambiguousByteCount;
          ambiguousByteCount=0;
        }
        str.append(buffer, startPosn, appendLength);
        txtLength += appendLength;
      }
      if (bufferPosn >= bufferLength) {
        if (delPosn > 0 && delPosn < recordDelimiterBytes.length) {
          ambiguousByteCount = delPosn;
          bytesConsumed -= ambiguousByteCount; //to be consumed in next
        }
      }
    } while (delPosn < recordDelimiterBytes.length 
        && bytesConsumed < maxBytesToConsume);
    if (bytesConsumed > Integer.MAX_VALUE) {
      throw new IOException("Too many bytes before delimiter: " + bytesConsumed);
    }
    return (int) bytesConsumed; 
  }

  /**
   * Read from the InputStream into the given Text.
   * @param str the object to store the given line
   * @param maxLineLength the maximum number of bytes to store into str.
   * @return the number of bytes read including the newline
   * @throws IOException if the underlying stream throws
   */
  public int readLine(Text str, int maxLineLength) throws IOException {
    return readLine(str, maxLineLength, Integer.MAX_VALUE);
  }

  /**
   * Read from the InputStream into the given Text.
   * @param str the object to store the given line
   * @return the number of bytes read including the newline
   * @throws IOException if the underlying stream throws
   */
  public int readLine(Text str) throws IOException {
    return readLine(str, Integer.MAX_VALUE, Integer.MAX_VALUE);
  }
}
