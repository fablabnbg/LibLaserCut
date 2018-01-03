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
  private String filename = "thunder.rd";
  private String name;
  /* overall dimensions */
  private double width = 0.0;
  private double height = 0.0;
  /* Layers */
  private ArrayList<Layer> layers;
  /* current layer */
  private Layer layer = null;
  private OutputStream out;
  /* pseudo-colors
   * black, red, green, blue, yellow, magenta, cyan, white
   */
  private static final int[] red =   {0, 100,   0,   0, 100, 100,   0, 100 };
  private static final int[] green = {0,   0, 100,   0, 100,   0, 100, 100 };
  private static final int[] blue =  {0,   0,   0, 100,   0, 100, 100, 100 };

  public Ruida()
  {
  }

  public void setName(String name)
  {
    this.name = name;
  }

  public void open() throws IOException
  {
    layers = new ArrayList<Layer>();

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
    int layers_with_vectors = 0;
    upload();
    writeHeader();
    for (int i = 0; i < layers.size(); i++)
    {
      layer = layers.get(i);
      if (layer.hasVectors()) {
        layer.setNumber(layers_with_vectors);
        layer.writePropertiesTo(out);
        layers_with_vectors += 1;
      }
    }
    layerCount(layers_with_vectors - 2);
    for (int i = 0; i < layers.size(); i++)
    {
      layer = layers.get(i);
      if (layer.hasVectors()) {
        layer.writeVectorsTo(out);
        travel_distance += layer.getTravelDistance();
      }
    }
    writeFooter(travel_distance);
  }

  public void close() throws IOException
  {
    out.close();
    out = null;
    layers = null;
  }

  public String getModelName()
  {
    return "Ruida";
  }

  /**
   * startPart
   * starts a Raster, Raster3d, or VectorPart
   *
   * internally translated to Layer
   */
  public void startPart(double top_left_x, double top_left_y, double width, double height)
  {
    int size = layers.size();
    this.width = Math.max(this.width, width);
    this.height = Math.max(this.height,height);

    layer = new Layer(size);
    layer.setDimensions(top_left_x, top_left_y, width, height);
    if (size > 0) {
      layer.setRGB(red[size%8], green[size%8], blue[size%8]);
    }
    layers.add(layer);
  }

  /**
   * endPart
   * just here for completeness
   */
  public void endPart()
  {
    return;
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

  /**
   * lineTo
   * coordinates in mm
   */

  public void lineTo(double x, double y) throws RuntimeException
  {
    layer.vectorTo(x, y, false);
  }

  /**
   * moveTo
   */
  public void moveTo(double x, double y) throws RuntimeException
  {
    layer.vectorTo(x, y, true);
  }

/*-------------------------------------------------------------------------*/

  /* upload as this.name */
  private void upload() throws IOException
  {
    writeHex("E802"); // prep filename
    /* filename */
    byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("E701"), Lib.stringToByteArray(this.name));
    writeData((byte[])ArrayUtils.addAll(res, Lib.hexStringToByteArray("00"))); // trailing zero
  }

  private void writeHeader() throws IOException
  {
    identifier();

    start();
    lightRed();
    feeding(0,0);
  }

  private void writeFooter(double travel_distance) throws IOException
  {
    workInterval(travel_distance);
    finish();
    stop();
    eof();
  }

  /**
   * scramble & write data
   */
  private void writeData(byte[] data) throws IOException
  {
    out.write(Lib.scramble(data));
  }

  /**
   * scramble & write hex string
   */
  private void writeHex(String hex) throws IOException
  {
    writeData(Lib.hexStringToByteArray(hex));
  }
  /**
   * layer count
   */
  private void layerCount(int count) throws IOException {
    if (count > 0) {
      writeData((byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("CA22"), Lib.intValueToByteArray(count)));
    }
  }

  /**
   * Feeding
   */
  private void feeding(double x, double y) throws IOException {
    byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("E706"), Lib.absValueToByteArray(x));
    writeData((byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(y)));
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
    writeData((byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(distance)));
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
