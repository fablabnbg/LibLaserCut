/**
 * This file is part of LibLaserCut.
 * Copyright (C) 2011 - 2014 Thomas Oster <mail@thomas-oster.de>
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

package com.t_oster.liblasercut.drivers;

import org.apache.commons.lang3.ArrayUtils;
import java.io.BufferedReader;
import java.io.PrintStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;


/**
 * Support for ThunderLaser lasers, just vector cuts.
 * 
 *  Based on FullSpectrumCutter
 * 
 * @author Klaus Kämpf <kkaempf@suse.de>
 */

public class Ruida
{
  public Ruida()
  {
  }

  private PrintStream out;

  public void open() throws IOException
  {
    if (getFilename() == null || getFilename().equals(""))
    {
      throw new IOException("Output filename must be set to upload via File method.");
    }
    File file = new File(getFilename());
    out = new PrintStream(new FileOutputStream(file));
  }

  public void close() throws IOException
  {
    out.close();
  }

  private String filename = "thunder.rd";

  /**
   * Get the value of output filename
   *
   * @return the value of filename
   */
  public String getFilename()
  {
    return filename;
  }

  /**
   * Set the value of output filename
   *
   * @param filename new value of filename
   */
  public void setFilename(String filename)
  {
    this.filename = filename;
  }

  // https://stackoverflow.com/questions/11208479/how-do-i-initialize-a-byte-array-in-java
  private static byte[] hexStringToByteArray(String s) {
    int len = s.length();
    byte[] data = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
      data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                            + Character.digit(s.charAt(i+1), 16));
    }
    return data;
  }

  private byte[] scramble(byte[] data)
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
   * percent value to double byte
   */
  private byte[] percentValueToByteArray(int percent) {
    float val = (float)(percent / 0.006103516); // 100/2^14
    return relValueToByteArray(val);
  }

  /**
   * integer value to single byte
   */
  private byte[] intValueToByteArray(int i) {
    byte[] data = new byte[1];
    data[0] = (byte)(i & 0xff);
    return data;
  }

  /**
   * relative value (double)
   * returns a 5-byte number
   */
  private byte[] relValueToByteArray(double f) {
    byte[] data = new byte[2];
    int val = (int)Math.round(f);
    for (int i = 0; i < 2; i++) {
      data[i] = (byte)(val & 0x7f);
      val = val >> 7;
    }
    ArrayUtils.reverse(data);
    return data;
  }

  /**
   * absolute value (double)
   * returns a 5-byte number
   */
  private byte[] absValueToByteArray(double f) {
    byte[] data = new byte[5];
    int val = (int)(f * 1000.0);
    for (int i = 0; i < 5; i++) {
      data[i] = (byte)(val & 0x7f);
      val = val >> 7;
    }
    ArrayUtils.reverse(data);
    return data;
  }

  /**
   * scramble & write data
   */
  public void write(byte[] data) throws IOException
  {
    out.write(scramble(data));
  }

  /**
   * scramble & write hex string
   */
  public void writeHex(String hex) throws IOException
  {
    write(hexStringToByteArray(hex));
  }

  /**
   * Cut absolute
   */
  public void cutAbs(double x, double y) throws IOException {
    byte[] res = (byte[])ArrayUtils.addAll(hexStringToByteArray("A8"), absValueToByteArray(x));
    write((byte[])ArrayUtils.addAll(res, absValueToByteArray(y)));
  }

  /**
   * Feeding
   */
  public void feeding(double x, double y) throws IOException {
    byte[] res = (byte[])ArrayUtils.addAll(hexStringToByteArray("E706"), absValueToByteArray(x));
    write((byte[])ArrayUtils.addAll(res, absValueToByteArray(y)));
  }

  /**
   * Cut dimensions
   * Top_Left_E7_07 0.0mm 0.0mm                      e7 03 00 00 00 00 00 00 00 00 00 00 
   * Bottom_Right_E7_07 52.0mm 53.0mm                e7 07 00 00 03 16 20 00 00 03 1e 08 
   * Top_Left_E7_50 0.0mm 0.0mm                      e7 50 00 00 00 00 00 00 00 00 00 00 
   * Bottom_Right_E7_51 52.0mm 53.0mm                e7 51 00 00 03 16 20 00 00 03 1e 08 
   * 
   */
  public void dimensions(double top_left_x, double top_left_y, double bottom_right_x, double bottom_right_y) throws IOException
  {
    byte[] res = (byte[])ArrayUtils.addAll(hexStringToByteArray("E703"), absValueToByteArray(top_left_x));
    write((byte[])ArrayUtils.addAll(res, absValueToByteArray(top_left_y)));
    res = (byte[])ArrayUtils.addAll(hexStringToByteArray("E707"), absValueToByteArray(bottom_right_x));
    write((byte[])ArrayUtils.addAll(res, absValueToByteArray(bottom_right_y)));
    res = (byte[])ArrayUtils.addAll(hexStringToByteArray("E750"), absValueToByteArray(top_left_x));
    write((byte[])ArrayUtils.addAll(res, absValueToByteArray(top_left_y)));
    res = (byte[])ArrayUtils.addAll(hexStringToByteArray("E751"), absValueToByteArray(bottom_right_x));
    write((byte[])ArrayUtils.addAll(res, absValueToByteArray(bottom_right_y)));
  }

  /**
   * speed (per layer)
   */
  public void layerSpeed(int layer, double speed) throws IOException
  {
    byte[] res = (byte[])ArrayUtils.addAll(hexStringToByteArray("C904"), intValueToByteArray(layer));
    write((byte[])ArrayUtils.addAll(res, absValueToByteArray(speed)));
  }

  /**
   * power (per laser)
   */
  public void layerLaserPower(int layer, int laser, int min_power, int max_power) throws IOException, RuntimeException
  {
    byte[] c6 = hexStringToByteArray("C6");
    byte[] min_hex, max_hex;
    switch (laser) {
    case 1:
      min_hex = hexStringToByteArray("31");
      max_hex = hexStringToByteArray("32");
      break;
    case 2:
      min_hex = hexStringToByteArray("41");
      max_hex = hexStringToByteArray("42");
      break;
    case 3:
      min_hex = hexStringToByteArray("35");
      max_hex = hexStringToByteArray("36");
      break;
    case 4:
      min_hex = hexStringToByteArray("37");
      max_hex = hexStringToByteArray("38");
      break;
    default:
      throw new RuntimeException("Illegal 'laser' value in Ruida.layerLaserPower");
    }
    byte[] res = (byte[])ArrayUtils.addAll(c6, min_hex);
    res = (byte[])ArrayUtils.addAll(res, intValueToByteArray(layer));
    write((byte[])ArrayUtils.addAll(res, percentValueToByteArray(min_power)));
    res = (byte[])ArrayUtils.addAll(c6, max_hex);
    res = (byte[])ArrayUtils.addAll(res, intValueToByteArray(layer));
    write((byte[])ArrayUtils.addAll(res, percentValueToByteArray(max_power)));
  }

  /**
   * write initial file header for model 644
   * @throws IOException
   */

  public void writeHeader() throws IOException
  {
    byte[] header = hexStringToByteArray("D29BFA");
    out.write(header);
  }

  /**
   * start
   */
  public void start() throws IOException
  {
    writeHex("F10200");
  }
  
  /**
   * lightRed
   */
  public void lightRed() throws IOException
  {
    writeHex("D800");
  }
  
  /**
   * finish
   */
  public void finish() throws IOException
  {
    writeHex("EB");
  }
  
  /**
   * stop
   */
  public void stop() throws IOException
  {
    writeHex("E700");
  }

  /**
   * eof
   */
  public void eof() throws IOException
  {
    writeHex("D7");
  }
  
}
