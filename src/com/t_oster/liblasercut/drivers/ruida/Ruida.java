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

package com.t_oster.liblasercut.drivers.ruida;

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
import java.util.ArrayList;
import com.t_oster.liblasercut.drivers.ruida.Layer;
import com.t_oster.liblasercut.drivers.ruida.Lib;

/**
 * Support for ThunderLaser lasers, just vector cuts.
 * 
 *  Based on FullSpectrumCutter
 * 
 * @author Klaus Kämpf <kkaempf@suse.de>
 */

public class Ruida
{
  /* overall dimensions */
  private double max_x = 0;
  private double max_y = 0;
  /* Layers */
  private ArrayList<Layer> layers;
  /* current layer */
  private Layer layer = null;
  private OutputStream out;

  public Ruida()
  {
    layers = new ArrayList<Layer>();
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
    System.out.println("Ruida: write()");
    double travel_distance = 0.0;
    layer = layers.get(0);
    writeHeader(layer.getBottomRightX(), layer.getBottomRightY());
    for (int i = 1; i < layers.size(); i++)
    {
      layer = layers.get(i);
      System.out.println("Ruida: write(layer " + i + ")");
      dimensions(layer.getTopLeftX(), layer.getTopLeftY(), layer.getBottomRightX(), layer.getBottomRightY());
      layer.writeTo(out);
      travel_distance += layer.getTravelDistance();
    }
    writeFooter(travel_distance);
  }

  public void close() throws IOException
  {
    out.close();
  }

  public void startJob(double top_left_x, double top_left_y, double width, double height)
  {
    layer = new Layer(layers.size());
    layer.setDimensions(top_left_x, top_left_y, width, height);
    layers.add(layer);
  }

  public void setFocus(float focus)
  {
    layer.setFocus(focus);
  }

  public void setFrequency(float frequency)
  {
    layer.setFrequency(frequency);
  }

  /**
   * lineTo
   * coordinates in mm
   */

  public void lineTo(double x, double y) throws IOException
  {
    System.out.println("lineTo(" + (float)x + ", " + (float)y + ")");
    layer.vectorTo(x * 1000.0, y * 1000.0, false);
  }

  /**
   * moveTo
   */
  public void moveTo(double x, double y) throws IOException
  {
    System.out.println("moveTo(" + (float)x + ", " + (float)y + ")");
    layer.vectorTo(x * 1000.0, y * 1000.0, true);
  }

  private void writeHeader(double max_x, double max_y) throws IOException
  {
    identifier();

    start();
    lightRed();
    feeding(0,0);
    dimensions(0, 0, max_x, max_y);
    writeHex("e7040001000100000000000000000000");
    writeHex("e70500");
  }

  private void writeFooter(double travel_distance) throws IOException
  {
    workInterval(travel_distance);
    finish();
    stop();
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

  public void setSpeed(double speed)
  {
    layer.setSpeed(speed);
  }

  public void setPower(int power)
  {
    layer.setPower(power, power);
  }

/* ----------------------------------------------------- */

  /**
   * scramble & write data
   */
  private void writeD(byte[] data) throws IOException
  {
    out.write(Lib.scramble(data));
  }

  /**
   * scramble & write hex string
   */
  private void writeHex(String hex) throws IOException
  {
    writeD(Lib.hexStringToByteArray(hex));
  }
  /**
   * Feeding
   */
  private void feeding(double x, double y) throws IOException {
    byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("E706"), Lib.absValueToByteArray(x));
    writeD((byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(y)));
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
    byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("E703"), Lib.absValueToByteArray(top_left_x));
    writeD((byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(top_left_y)));
    res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("E707"), Lib.absValueToByteArray(bottom_right_x));
    writeD((byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(bottom_right_y)));
    res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("E750"), Lib.absValueToByteArray(top_left_x));
    writeD((byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(top_left_y)));
    res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("E751"), Lib.absValueToByteArray(bottom_right_x));
    writeD((byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(bottom_right_y)));
  }

  /**
   * write initial file identifier for model 644
   * @throws IOException
   */

  private void identifier() throws IOException
  {
    byte[] head = Lib.hexStringToByteArray("D29BFA");
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
   * workInterval
   */
  private void workInterval(double distance) throws IOException
  {
    System.out.println("workInterval(" + distance + ")");
    byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("da010620"), Lib.absValueToByteArray(distance));
    writeD((byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(distance)));
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
