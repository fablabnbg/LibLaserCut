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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
/** either gnu.io or purejavacomm implement the SerialPort. Same API. **/
// import gnu.io.*;
// import purejavacomm.*;
import com.t_oster.liblasercut.drivers.ruida.Layer;
import com.t_oster.liblasercut.drivers.ruida.Lib;
import com.t_oster.liblasercut.drivers.ruida.Serial;
import com.t_oster.liblasercut.drivers.ruida.UdpStream;

/**
 * Support for ThunderLaser lasers, just vector cuts.
 *
 *  Based on FullSpectrumCutter
 *
 * @author Klaus Kämpf <kkaempf@suse.de>
 */

public class Ruida
{
  public static final int DEST_PORT = 50200; // fixed UDB port
  public static final int NETWORK_TIMEOUT = 3000;
  public static final int SOURCE_PORT = 40200; // used by rdworks in Windows
  private String name;
  /* overall bounding dimensions */
  private double boundingWidth = 0.0;
  private double boundingHeight = 0.0;
  private int boundingLayer = -1; // number of largest layer
  /* Layers */
  private ArrayList<Layer> layers;
  /* current layer */
  private Layer layer = null;
  private OutputStream out;
  private Serial serial;
  private File file;
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

  /**
   * open file output connection
   * @sets out
   */
  public void openFile(String filename) throws IOException, Exception
  {
    System.out.println("Ruida.open - normal disk file \"" + filename + "\"");
    file = new File(filename);
    // a normal disk file
    out = new PrintStream(new FileOutputStream(file));
  }
   
  /**
   * open network output connection
   * @sets out
   */
  public void openNetwork(String hostname) throws IOException, Exception
  {
    out = new UdpStream(hostname, DEST_PORT);
    return;
  }

  /**
   * open USB output connection
   * @sets out
   */
  public void openUsb(String device) throws IOException, Exception
  {
    if (!(serial instanceof Serial)) { // not open yet
      // the usb device, hopefully
      //
      try {
        System.out.println("Ruida.open - serial " + device);
        serial = new Serial();
        serial.open(device);
        out = serial.outputStream();
        writeHex("DA000004"); // identify
        serial.read(16);
      }
      catch (Exception e) {
        System.out.println("Looks like '" + device + "' is not a serial device");
        throw e;
      }
    }
  }

  public void close() throws IOException, Exception
  {
    System.out.println("Ruida.close()");
    if (serial instanceof Serial) {
      serial.close();
    }
    if (out instanceof UdpStream) {
      out.close();
    }
    serial = null;
    layers = null;
  }

  public void write() throws IOException
  {
    System.out.println("Ruida: write()");
    double travel_distance = 0.0;
    int layers_with_vectors = 0;
    upload();
    writeHeader();
    /* bounding box */
    layer = layers.get(this.boundingLayer);
    layer.writeBoundingBoxTo(out);
    /* layer declarations */
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
    /* layer definitions */
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

  public String getModelName()
  {
    System.out.println("Ruida.getModelName()");
    try {
      return new String(read("DA00057F")); // Version
    }
    catch (IOException e) {
      return "Failed";
    }
  }

  /**
   * startPart
   * starts a Raster, Raster3d, or VectorPart
   *
   * internally translated to Layer
   */
  public void startPart(double top_left_x, double top_left_y, double width, double height)
  {
    if (layers == null) {
      layers = new ArrayList<Layer>();
    }
    int number = layers.size(); // relative number of new layer
    if ((width > this.boundingWidth) || (height > this.boundingHeight)) {
      this.boundingWidth = width;
      this.boundingHeight = height;
      this.boundingLayer = number;
    }

    layer = new Layer(number);
    layer.setDimensions(top_left_x, top_left_y, top_left_x+width, top_left_y+height);
    if (number > 0) {
      // 'random' color
      layer.setRGB(red[number%8], green[number%8], blue[number%8]);
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

  public void setFrequency(int frequency)
  {
    layer.setFrequency(frequency);
  }

  public void setSpeed(int speed)
  {
    layer.setSpeed(speed);
  }

  public void setMinPower(int power)
  {
    layer.setMinPower(power);
  }

  public void setMaxPower(int power)
  {
    layer.setMaxPower(power);
  }

  public double getBedWidth() throws Exception
  {
    double value;
    System.out.println("Ruida.getBedWidth");
    openUsb("/dev/ttyUSB0");
    value = absValueAt(read("DA000026"), 0) / 1000.0;
    close();
    return value;
  }

  public double getBedHeight() throws Exception
  {
    double value;
    System.out.println("Ruida.getBedHeight");
    openUsb("/dev/ttyUSB0");
    value = absValueAt(read("DA000036"), 0) / 1000.0;
    close();
    return value;
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

  private long absValueAt(byte[] data, int offset) throws Exception
  {
    if (data.length < offset + 5) {
      System.out.println("Insufficient data for absolute value");
      throw new Exception("Insufficient data for absolute value");
    }
    long result = 0;
    int factor = 1;
    for (int i = 4; i >= 0; i--) {
      int val = data[offset+i];
      result += val * factor;
      factor *= 127;
    }
    System.out.println(String.format("Ruida.absValueAt(%d) = %ld", offset, result));
    return result;
  }

  private byte[] read(String command) throws IOException
  {
    System.out.println("Ruida.read(" + command + ")");
    try {
      writeHex(command);
      byte[] data = Lib.unscramble(serial.read(32));
      if (data.length > 4) {
        return Arrays.copyOfRange(data, 4, data.length);
      }
      else {
        System.out.println("insufficient read !");
        return Arrays.copyOfRange(data, 0, 0);
      }
    }
    catch (Exception e) {
      e.printStackTrace();
      throw new IOException();
    }
  }

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
//    System.out.println("Ruida.writeData " + data.length);
    if (out == null) {
      throw new IOException("No output configured");
    }
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
