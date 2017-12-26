/**
 * This file is part of LibLaserCut.
 * Copyright (C) 2017 Klaus Kämpf <kkaempf@suse.de>
 *
 * LibLaserCut is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LibLaserCut is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with LibLaserCut. If not, see <http://www.gnu.org/licenses/>.
 *
 **/

package com.t_oster.liblasercut.drivers.ruida;

import org.apache.commons.lang3.ArrayUtils;

public class Lib
{
  /**
   * https://stackoverflow.com/questions/11208479/how-do-i-initialize-a-byte-array-in-java
   */
  public static byte[] hexStringToByteArray(String s) {
    int len = s.length();
    byte[] data = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
      data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                            + Character.digit(s.charAt(i+1), 16));
    }
    return data;
  }

  public static byte[] scramble(byte[] data)
  {
    for (int i = 0; i < data.length; i++) {
      int val = data[i] & 0xff;
      int hbit = (val >> 7) & 0x01;
      int lbit = val & 0x01;
      val = (val & 0x7e) + hbit + (lbit<<7);
      val = val ^ 0x88;
      data[i] = (byte)((val + 1) & 0xff);
    }
    return data;
  }
  
  /**
   * percent value to 2-bytes
   */
  public static byte[] percentValueToByteArray(int percent) {
    double val = percent / 0.006103516; // 100/2^14
//    System.out.println("percentValueToByteArray(" + percent + " -> " + val + ")");
    return relUnsignedValueToByteArray(val);
  }

  /**
   * integer value to single byte
   */
  public static byte[] intValueToByteArray(int i) {
    byte[] data = new byte[1];
    data[0] = (byte)(i & 0xff);
    return data;
  }

  public static byte[] relSignedValueToByteArray(double d) {
    return relValueToByteArray(d, true);
  }
  public static byte[] relUnsignedValueToByteArray(double d) {
    return relValueToByteArray(d, false);
  }
  /**
   * relative value (double)
   * returns a 2-byte number
   */
  private static byte[] relValueToByteArray(double d, boolean signed) {
    byte[] data = new byte[2];
    int val = (int)Math.floor(d);
//    System.out.println("rel" + ((signed)?"Signed":"Unsigned") + "ValueToByteArray(" + d + " -> " + val + ")");
    if (signed) {
      if (val > 8191) {
        //      System.out.println("relValueToByteArray(" + val + ") > 8191");
        throw new IllegalArgumentException("Relative signed value > 8191");
      }
      else if (val < -8192) {
        //      System.out.println("relValueToByteArray(" + val + ") < 8192");
        throw new IllegalArgumentException("Relative signed value < -8192");
      }
      else if (val < 0) {
        val = val + 16384;
      }
    }
    else {
      if (val > 16383) {
        throw new IllegalArgumentException("Relative unsigned value > 16383");
      }
      else if (val < 0) {
        throw new IllegalArgumentException("Relative unsigned value < 0");
      }
    }
    for (int i = 0; i < 2; i++) {
      data[i] = (byte)(val & 0x7f);
      val = val >> 7;
    }
    ArrayUtils.reverse(data);
    return data;
  }

  /**
   * absolute value (float)
   * returns a 5-byte number
   */
  public static byte[] absValueToByteArray(double d) {
    byte[] data = new byte[5];
    int val = (int)d;
    for (int i = 0; i < 5; i++) {
      data[i] = (byte)(val & 0x7f);
      val = val >> 7;
    }
    ArrayUtils.reverse(data);
    return data;
  }

}
