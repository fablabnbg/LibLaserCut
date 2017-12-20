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
   * absolute coordinate in mm (double)
   */
  private byte[] absCoordToByteArray(double f) {
    byte[] data = new byte[5];
    int fak = 0x80;
    int val = (int)(f * 1000.0);
    for (int i = 0; i < 5; i++) {
      data[i] = (byte)(val & 0x7f);
      val = val >> 7;
    }
    ArrayUtils.reverse(data);
    return data;
  }

  /**
   * Cut absolute
   */
  public void cutAbs(double x, double y) throws IOException {
    byte[] res = (byte[])ArrayUtils.addAll(hexStringToByteArray("A8"), absCoordToByteArray(x));
    out.write(scramble((byte[])ArrayUtils.addAll(res, absCoordToByteArray(y))));
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
   * finish
   */
  public void finish() throws IOException
  {
    out.write(scramble(hexStringToByteArray("EB")));
  }
  
  /**
   * stop
   */
  public void stop() throws IOException
  {
    out.write(scramble(hexStringToByteArray("E700")));
  }

  /**
   * eof
   */
  public void eof() throws IOException
  {
    out.write(scramble(hexStringToByteArray("D7")));
  }
  
}
