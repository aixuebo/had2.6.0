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
package org.apache.hadoop.hdfs;

import java.util.List;
import java.util.Map;

import org.apache.hadoop.HadoopIllegalArgumentException;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.fs.XAttr;
import org.apache.hadoop.fs.XAttr.NameSpace;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * 获取属性和值的简单对象
 */
@InterfaceAudience.Private
public class XAttrHelper {
  
  /**
   * Build <code>XAttr</code> from xattr name with prefix.
   * 为属性赋null值
   */
  public static XAttr buildXAttr(String name) {
    return buildXAttr(name, null);
  }
  
  /**
   * Build <code>XAttr</code> from name with prefix and value.
   * Name can not be null. Value can be null. The name and prefix 
   * are validated.
   * Both name and namespace are case sensitive.
   * 格式:
   * name格式:NameSpace.key
   * value是值
   * 为属性赋值
   */
  public static XAttr buildXAttr(String name, byte[] value) {
    Preconditions.checkNotNull(name, "XAttr name cannot be null.");
    
    final int prefixIndex = name.indexOf(".");
    if (prefixIndex < 3) {// Prefix length is at least 3.因为安全级别最小的也是raw,为3个字节
      throw new HadoopIllegalArgumentException("An XAttr name must be " +
          "prefixed with user/trusted/security/system/raw, followed by a '.'");
    } else if (prefixIndex == name.length() - 1) {
      throw new HadoopIllegalArgumentException("XAttr name cannot be empty.");
    }
    
    NameSpace ns;
    final String prefix = name.substring(0, prefixIndex).toLowerCase();//获取安全级别
    if (prefix.equals(NameSpace.USER.toString().toLowerCase())) {
      ns = NameSpace.USER;
    } else if (prefix.equals(NameSpace.TRUSTED.toString().toLowerCase())) {
      ns = NameSpace.TRUSTED;
    } else if (prefix.equals(NameSpace.SYSTEM.toString().toLowerCase())) {
      ns = NameSpace.SYSTEM;
    } else if (prefix.equals(NameSpace.SECURITY.toString().toLowerCase())) {
      ns = NameSpace.SECURITY;
    } else if (prefix.equals(NameSpace.RAW.toString().toLowerCase())) {
      ns = NameSpace.RAW;
    } else {
      throw new HadoopIllegalArgumentException("An XAttr name must be " +
          "prefixed with user/trusted/security/system/raw, followed by a '.'");
    }
    XAttr xAttr = (new XAttr.Builder()).setNameSpace(ns).setName(name.substring(prefixIndex + 1)).setValue(value).build();
    
    return xAttr;
  }
  
  /**
   * Build xattr name with prefix as <code>XAttr</code> list.
   * 将name对应的参数值设置为null,同时添加到集合中返回
   */
  public static List<XAttr> buildXAttrAsList(String name) {
    XAttr xAttr = buildXAttr(name);//先为name赋值为null
    List<XAttr> xAttrs = Lists.newArrayListWithCapacity(1);//
    xAttrs.add(xAttr);
    
    return xAttrs;
  }
  
  /**
   * Get value of first xattr from <code>XAttr</code> list
   * 获取集合中第一个对象的value字节数组值,如果该value为null,则赋值为空字节数组
   */
  public static byte[] getFirstXAttrValue(List<XAttr> xAttrs) {
    byte[] value = null;
    //获取集合中第一个对象返回
    XAttr xAttr = getFirstXAttr(xAttrs);
    if (xAttr != null) {
      value = xAttr.getValue();
      if (value == null) {
        value = new byte[0]; // xattr exists, but no value.
      }
    }
    return value;
  }
  
  /**
   * Get first xattr from <code>XAttr</code> list
   * 获取集合中第一个对象返回
   */
  public static XAttr getFirstXAttr(List<XAttr> xAttrs) {
    if (xAttrs != null && !xAttrs.isEmpty()) {
      return xAttrs.get(0);
    }
    
    return null;
  }
  
  /**
   * Build xattr map from <code>XAttr</code> list, the key is 
   * xattr name with prefix, and value is xattr value. 
   * 将参数集合中数据转化成map形式,key是NameSpace.name,value是对应的值
   */
  public static Map<String, byte[]> buildXAttrMap(List<XAttr> xAttrs) {
    if (xAttrs == null) {
      return null;
    }
    Map<String, byte[]> xAttrMap = Maps.newHashMap();
    for (XAttr xAttr : xAttrs) {
      String name = getPrefixName(xAttr);//NameSpace.name
      byte[] value = xAttr.getValue();//该元素对应的值
      if (value == null) {
        value = new byte[0];
      }
      xAttrMap.put(name, value);
    }
    
    return xAttrMap;
  }
  
  /**
   * Get name with prefix from <code>XAttr</code>
   * 通过该属性对象，返回为NameSpace.name
   */
  public static String getPrefixName(XAttr xAttr) {
    if (xAttr == null) {
      return null;
    }
    
    String namespace = xAttr.getNameSpace().toString();
    return namespace.toLowerCase() + "." + xAttr.getName();
  }

  /**
   * Build <code>XAttr</code> list from xattr name list.
   * 获取name对应的属性集合,value设置的值都是null
   */
  public static List<XAttr> buildXAttrs(List<String> names) {
    if (names == null || names.isEmpty()) {
      throw new HadoopIllegalArgumentException("XAttr names can not be " +
          "null or empty.");
    }
    
    List<XAttr> xAttrs = Lists.newArrayListWithCapacity(names.size());
    for (String name : names) {
      xAttrs.add(buildXAttr(name, null));
    }
    return xAttrs;
  } 
}
