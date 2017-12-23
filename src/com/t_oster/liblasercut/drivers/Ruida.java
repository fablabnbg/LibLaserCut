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
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
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
  private double frequency = 0.0;

  private OutputStream out;
  /* internal buffer of vector cmds */
  private ByteArrayOutputStream vectors;

  public Ruida()
  {
    this.vectors = new ByteArrayOutputStream();
  }

  public void open() throws IOException
  {
    if (getFilename() == null || getFilename().equals(""))
    {
      throw new IOException("Output filename must be set to upload via File method.");
    }
    File file = new File(getFilename());
    out = new PrintStream(new FileOutputStream(file));
  }

  public void write() throws IOException
  {
    writeHeader();
    writeVectors();
    writeFooter();
  }

  public void close() throws IOException
  {
    out.close();
  }

  private void writeHeader() throws IOException
  {
    header();

    start();
    lightRed();
    feeding(0,0);
    dimensions(0, 0, max_x, max_y);
    writeHex("e7040001000100000000000000000000");
    writeHex("e70500");

    blowOn();
    layerSpeed(layer, this.speed);

    layerLaserPower(layer, 1, 14, this.power);
    layerLaserPower(layer, 2, 18, 30);
    layerLaserPower(layer, 3, 30, 30);
    layerLaserPower(layer, 4, 30, 30);
  }

  private void writeVectors() throws IOException
  {
    vectors.writeTo(out);
  }
  
  private void writeFooter() throws IOException
  {
    finish();
    stop();
    // ruida.workInterval();
    eof();
  }

  /**
   * filename
   */
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

  /**
   * speed
   */
  private double speed = 0.0;

  public double getSpeed()
  {
    return speed;
  }
  public void setSpeed(double s)
  {
    speed = s;
  }

  /**
   * power
   */
  private int power = 0;
  public int getPower()
  {
    return power;
  }
  public void setPower(int p)
  {
    power = p;
  }
  
  
  /**
   * layer
   */
  private int layer = 0;


  private double max_x = 0;
  private double max_y = 0;
  private double xsim = 0;
  private double ysim = 0;
  private double overall_distance = 0;

  /**
   * check distance
   * 
   * compute max_x, max_y, and overall_distance
   * 
   * return
   *   0 - distance is absolute
   *   1 - distance is relative
   *   2 - distance is horizontal
   *   3 - distance is vertical
   */

  private int checkDistance(double x, double y)
  {
    double dx = xsim - x;
    double dy = ysim - y;

    double distance = Math.sqrt(dx*dx + dy*dy);
    
    overall_distance += distance;
    // estimate the new real position
    xsim += Math.round((x-xsim) * 1000) / 1000d;
    ysim += Math.round((y-ysim) * 1000) / 1000d;
    max_x = Math.max(max_x, xsim);
    max_y = Math.max(max_y, ysim);
    if (distance > 8.191) {
      return 0;
    }
    else if (distance < -8.191) {
      return 0;
    }
    else {
      if (dx == 0) {
        return 3;
      }
      else if (dy == 0) {
        return 2;
      }
      else {
        return 1;
      }
    }
  }

  /**
   * lineTo
   */

  public void lineTo(double x, double y) throws IOException
  {
    System.out.println("lineTo(" + x + ", " + y + ")");
    switch(checkDistance(x, y)) {
    case 0:
      cutAbs(x, y);
      break;
    case 1:
      cutRel(x, y);
      break;
    case 2:
      cutHoriz(x);
      break;
    case 3:
      cutVert(y);
      break;
    }
  }

  /**
   * moveTo
   */
  public void moveTo(double x, double y) throws IOException
  {
    System.out.println("moveTo(" + x + ", " + y + ")");
    switch(checkDistance(x, y)) {
    case 0:
      moveAbs(x, y);
      break;
    case 1:
      moveRel(x, y);
      break;
    case 2:
      moveHoriz(x);
      break;
    case 3:
      moveVert(y);
      break;
    }
  }

/* ----------------------------------------------------- */

  /**
   * https://stackoverflow.com/questions/11208479/how-do-i-initialize-a-byte-array-in-java
   */
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
   * percent value to 2-bytes
   */
  private byte[] percentValueToByteArray(int percent) {
    double val = percent / 0.006103516; // 100/2^14
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
   * returns a 2-byte number
   */
  private byte[] relValueToByteArray(double d) {
    byte[] data = new byte[2];
    System.out.println("relValueToByteArray(" + d + ")");
    int val = (int)Math.round(d);
    if (val > 8191) {
      System.out.println("relValueToByteArray(" + val + ") > 8191");
      throw new IllegalArgumentException();
    }
    else if (val < -8192) {
      System.out.println("relValueToByteArray(" + val + ") < 8192");
      throw new IllegalArgumentException();
    }
    else if (val < 0) {
      val = val + 16384;
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
  private byte[] absValueToByteArray(double d) {
    byte[] data = new byte[5];
    int val = (int)(d * 1000.0);
    for (int i = 0; i < 5; i++) {
      data[i] = (byte)(val & 0x7f);
      val = val >> 7;
    }
    ArrayUtils.reverse(data);
    return data;
  }

  /**
   * scramble & write vector data
   */
  private void writeV(byte[] data) throws IOException
  {
    vectors.write(scramble(data));
  }

  /**
   * scramble & write data
   */
  private void writeD(byte[] data) throws IOException
  {
    out.write(scramble(data));
  }

  /**
   * scramble & write hex string
   */
  private void writeHex(String hex) throws IOException
  {
    writeD(hexStringToByteArray(hex));
  }

  /**
   * Move absolute
   */
  private void moveAbs(double x, double y) throws IOException
  {
    byte[] res = (byte[])ArrayUtils.addAll(hexStringToByteArray("88"), absValueToByteArray(x));
    writeV((byte[])ArrayUtils.addAll(res, absValueToByteArray(y)));
  }

  /**
   * Move relative
   */
  private void moveRel(double x, double y) throws IOException
  {
    byte[] res = (byte[])ArrayUtils.addAll(hexStringToByteArray("89"), relValueToByteArray(x));
    writeV((byte[])ArrayUtils.addAll(res, relValueToByteArray(y)));
  }

  /**
   * Move relative horizontal
   */
  private void moveHoriz(double x) throws IOException
  {
    byte[] res = (byte[])ArrayUtils.addAll(hexStringToByteArray("8A"), relValueToByteArray(x));
    writeV(res);
  }

  /**
   * Move relative vertical
   */
  private void moveVert(double y) throws IOException
  {
    byte[] res = (byte[])ArrayUtils.addAll(hexStringToByteArray("8B"), relValueToByteArray(y));
    writeV(res);
  }

  /**
   * Cut relative horizontal
   */
  private void cutHoriz(double x) throws IOException
  {
    byte[] res = (byte[])ArrayUtils.addAll(hexStringToByteArray("AA"), relValueToByteArray(x));
    writeV(res);
  }

  /**
   * Cut relative vertical
   */
  private void cutVert(double y) throws IOException
  {
    byte[] res = (byte[])ArrayUtils.addAll(hexStringToByteArray("AB"), relValueToByteArray(y));
    writeV(res);
  }

  /**
   * Cut relative
   */
  private void cutRel(double x, double y) throws IOException
  {
    byte[] res = (byte[])ArrayUtils.addAll(hexStringToByteArray("A9"), relValueToByteArray(x));
    writeV((byte[])ArrayUtils.addAll(res, relValueToByteArray(y)));
  }

  /**
   * Cut absolute
   */
  private void cutAbs(double x, double y) throws IOException
  {
    byte[] res = (byte[])ArrayUtils.addAll(hexStringToByteArray("A8"), absValueToByteArray(x));
    writeV((byte[])ArrayUtils.addAll(res, absValueToByteArray(y)));
  }

  /**
   * Feeding
   */
  private void feeding(double x, double y) throws IOException {
    byte[] res = (byte[])ArrayUtils.addAll(hexStringToByteArray("E706"), absValueToByteArray(x));
    writeD((byte[])ArrayUtils.addAll(res, absValueToByteArray(y)));
  }

  /**
   * Cut dimensions
   * Top_Left_E7_07 0.0mm 0.0mm                      e7 03 00 00 00 00 00 00 00 00 00 00 
   * Bottom_Right_E7_07 52.0mm 53.0mm                e7 07 00 00 03 16 20 00 00 03 1e 08 
   * Top_Left_E7_50 0.0mm 0.0mm                      e7 50 00 00 00 00 00 00 00 00 00 00 
   * Bottom_Right_E7_51 52.0mm 53.0mm                e7 51 00 00 03 16 20 00 00 03 1e 08 
   * 
   */
  private void dimensions(double top_left_x, double top_left_y, double bottom_right_x, double bottom_right_y) throws IOException
  {
    byte[] res = (byte[])ArrayUtils.addAll(hexStringToByteArray("E703"), absValueToByteArray(top_left_x));
    writeD((byte[])ArrayUtils.addAll(res, absValueToByteArray(top_left_y)));
    res = (byte[])ArrayUtils.addAll(hexStringToByteArray("E707"), absValueToByteArray(bottom_right_x));
    writeD((byte[])ArrayUtils.addAll(res, absValueToByteArray(bottom_right_y)));
    res = (byte[])ArrayUtils.addAll(hexStringToByteArray("E750"), absValueToByteArray(top_left_x));
    writeD((byte[])ArrayUtils.addAll(res, absValueToByteArray(top_left_y)));
    res = (byte[])ArrayUtils.addAll(hexStringToByteArray("E751"), absValueToByteArray(bottom_right_x));
    writeD((byte[])ArrayUtils.addAll(res, absValueToByteArray(bottom_right_y)));
  }

  /**
   * speed (per layer)
   */
  private void layerSpeed(int layer, double speed) throws IOException
  {
    byte[] res = (byte[])ArrayUtils.addAll(hexStringToByteArray("C904"), intValueToByteArray(layer));
    writeD((byte[])ArrayUtils.addAll(res, absValueToByteArray(speed)));
  }

  /**
   * power (per laser)
   */
  private void layerLaserPower(int layer, int laser, int min_power, int max_power) throws IOException, RuntimeException
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
    writeD((byte[])ArrayUtils.addAll(res, percentValueToByteArray(min_power)));
    res = (byte[])ArrayUtils.addAll(c6, max_hex);
    res = (byte[])ArrayUtils.addAll(res, intValueToByteArray(layer));
    writeD((byte[])ArrayUtils.addAll(res, percentValueToByteArray(max_power)));
  }

  /**
   * write initial file header for model 644
   * @throws IOException
   */

  private void header() throws IOException
  {
    byte[] head = hexStringToByteArray("D29BFA");
    out.write(head);
  }

  /**
   * start
   */
  private void start() throws IOException
  {
    writeHex("F10200");
  }
  
  /**
   * lightRed
   */
  private void lightRed() throws IOException
  {
    writeHex("D800");
  }

  /**
   * blowOn
   * without, the laser does not turn off at the end of the job
   */
  private void blowOn() throws IOException
  {
    writeHex("ca0113");
  }

  /**
   * finish
   */
  private void finish() throws IOException
  {
    writeHex("EB");
  }
  
  /**
   * stop
   */
  private void stop() throws IOException
  {
    writeHex("E700");
  }

  /**
   * eof
   */
  private void eof() throws IOException
  {
    writeHex("D7");
  }
  
}
