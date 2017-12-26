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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import com.t_oster.liblasercut.drivers.ruida.Lib;

/**
 * Layer
 * 
 * Represents a set of data with the same power, frequency, and speed
 *
 * - accumulates properties (speed, power, color, ...) in data ByteArrayOutputStream
 * - accumulates vectors (move, cut) in vectors ByteArrayOutputStream
 * - writeTo(output) assembles both and writes to output
 */

public class Layer
{
  private int number;
  /* layer properties */
  private double top_left_x = 0.0;
  private double top_left_y = 0.0;
  private double bottom_right_x = 0.0;
  private double bottom_right_y = 0.0;
  private int min_power = 0;
  private int max_power = 0;
  private double speed = 0.0;
  private double frequency = 0.0;
  private double focus = 0.0;
  private int red = 0;
  private int green = 0;
  private int blue = 0;
  private ByteArrayOutputStream data;
  private ByteArrayOutputStream vectors;
  private double travel_distance;
  private double xsim = 0.0;
  private double ysim = 0.0;
  private double max_x = 0.0;
  private double max_y = 0.0;

  /**
   * create new Layer
   * @number : -1 - 'frame layer' - overall size, no vectors
   *           0..255 - normal layer
   */
  public Layer(int number)
  {
    this.data = new ByteArrayOutputStream();
    if (number < -1) {
      throw new IllegalArgumentException("Layer number < -1");
    }
    if (number > 255) {
      throw new IllegalArgumentException("Layer number > 255");
    }
    this.number = number;
    if (this.number >= 0) {
      this.vectors = new ByteArrayOutputStream();
      /* start vector mode */
      writeHex(vectors, "ca030f");
      writeHex(vectors, "ca1000");
    }
  }
  /*
   * Layer dimensions
   */
  public void setDimensions(double top_left_x, double top_left_y, double bottom_right_x, double bottom_right_y)
  {
    System.out.println("Layer.dimensions(" + top_left_x + ", " + top_left_y + ", " + bottom_right_x + ", " + bottom_right_y + ")");
    if (top_left_x < 0) {
      throw new IllegalArgumentException("Layer top_left_x < 0");
    }
    if (top_left_y < 0) {
      throw new IllegalArgumentException("Layer top_left_y < 0");
    }
    if (bottom_right_x < 0) {
      throw new IllegalArgumentException("Layer bottom_right_x < 0");
    }
    if (bottom_right_x <= top_left_x) {
      throw new IllegalArgumentException("Layer bottom_right_x <= top_left_x");
    }
    if (bottom_right_y < 0) {
      throw new IllegalArgumentException("Layer bottom_right_y < 0");
    }
    if (bottom_right_y <= top_left_y) {
      throw new IllegalArgumentException("Layer bottom_right_y <= top_left_y");
    }
    this.top_left_x = top_left_x * 1000.0;
    this.top_left_y = top_left_y * 1000.0;
    this.bottom_right_x = bottom_right_x * 1000.0;
    this.bottom_right_y = bottom_right_y * 1000.0;
  }

  /**
   * vector
   * 
   * compute max_x, max_y, and travel_distance
   * 
   */
  public void vectorTo(double x, double y, boolean as_move) throws RuntimeException
  {
    /* convert to µm for Ruida */
    x = x * 1000.0;
    y = y * 1000.0;
    double dx = x - xsim;
    double dy = y - ysim;

    if (this.number == -1) {
      throw new RuntimeException("Layer.vectorTo for frame layer");
    }
    if ((dx == 0) && (dy == 0)) {
      return;
    }
    double distance = Math.sqrt(dx*dx + dy*dy);

    if (as_move) {
      travel_distance += distance;
    }
    // estimate the new real position
    xsim += dx;
    ysim += dy;
    max_x = Math.max(max_x, xsim);
    max_y = Math.max(max_y, ysim);
    if ((distance > 8191) || (distance < -8191)) {
      if (as_move) {
        moveAbs(x, y);
      }
      else {
        cutAbs(x, y);
      }
    }
    else if (dx == 0) {
      if (as_move) {
        moveVert(dy);
      }
      else {
        cutVert(dy);
      }
    }
    else if (dy == 0) {
      if (as_move) {
        moveHoriz(dx);
      }
      else {
        cutHoriz(dx);
      }
    }
    else {
      if (as_move) {
        moveRel(dx, dy);
      }
      else {
        cutRel(dx, dy);
      }
    }
  }

  public double getTravelDistance()
  {
    return this.travel_distance / 1000.0;
  }

  /**
   * write data as layer to out
   *
   */
  public void writePropertiesTo(OutputStream out) throws IOException
  {
    System.out.println("Layer.writeLayerTo(" + this.number + ") ");
    if (this.number == -1) {
      dimensions(top_left_x, top_left_y, bottom_right_x, bottom_right_y);
      writeHex(data, "e7040001000100000000000000000000");
      writeHex(data, "e70500");
      data.writeTo(out); data.reset();
    }
    else {
      layerSpeed(speed);
      laserPower(1, min_power, max_power);
      layerColor();
      layerCa41();
      dimensions(top_left_x, top_left_y, bottom_right_x, bottom_right_y);
      data.writeTo(out); data.reset();      
    }
  }

  /**
   * write vectors as layer to out
   *
   */
  public void writeVectorsTo(OutputStream out) throws IOException
  {
    System.out.println("Layer.writeVectorsTo(" + this.number + ") " + vectors.size() + " vector bytes");
    writeHex(data, "ca0100");
    prio(this.number);
    blowOn();
    speedC9(speed);
    power(1, min_power, max_power);
    data.writeTo(out); data.reset();      

    vectors.writeTo(out); vectors.reset();
  }

  /**
   * property setters
   */

  public void setSpeed(double speed)
  {
    System.out.println("Layer.setSpeed(" + speed + ")");
    this.speed = speed;
  }
  public void setFrequency(double frequency)
  {
    System.out.println("Layer.setFrequency(" + frequency + ")");
    this.frequency = frequency;
  }
  public void setFocus(double focus)
  {
    System.out.println("Layer.setFocus(" + focus + ")");
    this.focus = focus;
  }
  /**
   * set min/max power in %
   */
  public void setPower(int min_power, int max_power)
  {
    this.min_power = min_power;
    this.max_power = max_power;
  }
  /**
   * set RGB for preview display
   */
  public void setRGB(int red, int green, int blue) throws RuntimeException
  {
    if (this.number == -1) {
      throw new RuntimeException("Layer.setRGB for frame layer");
    }
    this.red = red & 0xff;
    this.green = green & 0xff;
    this.blue = blue & 0xff;
  }

/*-----------------------------------------------------------------*/

  /**
   * scramble & write vector data
   */
  private void write(OutputStream stream, byte[] bytes)
  {
    try {
      stream.write(Lib.scramble(bytes));
    } catch (IOException ex) {
      System.out.println("IOException in ruida.Layer.write");
    }
  }

  /**
   * scramble & write hex string
   */
  private void writeHex(OutputStream stream, String hex)
  {
    write(stream, Lib.hexStringToByteArray(hex));
  }

  /**
   * Move absolute
   */
  private void moveAbs(double x, double y)
  {
//    System.out.println("moveAbs(" + x + ", " + y + ")");
    byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("88"), Lib.absValueToByteArray(x));
    write(vectors, (byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(y)));
  }

  /**
   * Move relative
   */
  private void moveRel(double x, double y)
  {
//    System.out.println("moveRel(" + x + ", " + y + ")");
    byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("89"), Lib.relSignedValueToByteArray(x));
    write(vectors, (byte[])ArrayUtils.addAll(res, Lib.relSignedValueToByteArray(y)));
  }

  /**
   * Move relative horizontal
   */
  private void moveHoriz(double x)
  {
//    System.out.println("moveHoriz(" + x + ")");
    byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("8A"), Lib.relSignedValueToByteArray(x));
    write(vectors, res);
  }

  /**
   * Move relative vertical
   */
  private void moveVert(double y)
  {
//    System.out.println("moveVert(" + y + ")");
    byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("8B"), Lib.relSignedValueToByteArray(y));
    write(vectors, res);
  }

  /**
   * Cut relative horizontal
   */
  private void cutHoriz(double x)
  {
//    System.out.println("cutHoriz(" + x + ")");
    byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("AA"), Lib.relSignedValueToByteArray(x));
    write(vectors, res);
  }

  /**
   * Cut relative vertical
   */
  private void cutVert(double y)
  {
//    System.out.println("cutVert(" + y + ")");
    byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("AB"), Lib.relSignedValueToByteArray(y));
    write(vectors, res);
  }

  /**
   * Cut relative
   */
  private void cutRel(double x, double y)
  {
//    System.out.println("cutRel(" + x + ", " + y + ")");
    byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("A9"), Lib.relSignedValueToByteArray(x));
    write(vectors, (byte[])ArrayUtils.addAll(res, Lib.relSignedValueToByteArray(y)));
  }

  /**
   * Cut absolute
   */
  private void cutAbs(double x, double y)
  {
//    System.out.println("cutAbs(" + x + ", " + y + ")");
    byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("A8"), Lib.absValueToByteArray(x));
    write(vectors, (byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(y)));
  }

  /**
   * power (per laser)
   */
  private void laserPower(int laser, int min_power, int max_power) throws RuntimeException
  {
    if (this.number == -1) {
      throw new RuntimeException("Layer.laserPower for frame layer");
    }
    byte[] c6 = Lib.hexStringToByteArray("C6");
    byte[] min_hex, max_hex;
    switch (laser) {
    case 1:
      min_hex = Lib.hexStringToByteArray("31");
      max_hex = Lib.hexStringToByteArray("32");
      break;
    case 2:
      min_hex = Lib.hexStringToByteArray("41");
      max_hex = Lib.hexStringToByteArray("42");
      break;
    case 3:
      min_hex = Lib.hexStringToByteArray("35");
      max_hex = Lib.hexStringToByteArray("36");
      break;
    case 4:
      min_hex = Lib.hexStringToByteArray("37");
      max_hex = Lib.hexStringToByteArray("38");
      break;
    default:
      throw new RuntimeException("Illegal 'laser' value in Layer.laserPower");
    }
    byte[] res = (byte[])ArrayUtils.addAll(c6, min_hex);
    res = (byte[])ArrayUtils.addAll(res, Lib.intValueToByteArray(this.number));
    write(data, (byte[])ArrayUtils.addAll(res, Lib.percentValueToByteArray(min_power)));
    res = (byte[])ArrayUtils.addAll(c6, max_hex);
    res = (byte[])ArrayUtils.addAll(res, Lib.intValueToByteArray(this.number));
    write(data, (byte[])ArrayUtils.addAll(res, Lib.percentValueToByteArray(max_power)));
  }

  /**
   * power (for current layer)
   */
  private void power(int laser, int min_power, int max_power) throws RuntimeException
  {
    byte[] c6 = Lib.hexStringToByteArray("C6");
    byte[] min_hex, max_hex;
    switch (laser) {
    case 1:
      min_hex = Lib.hexStringToByteArray("01");
      max_hex = Lib.hexStringToByteArray("02");
      break;
    case 2:
      min_hex = Lib.hexStringToByteArray("21");
      max_hex = Lib.hexStringToByteArray("22");
      break;
    case 3:
      min_hex = Lib.hexStringToByteArray("05");
      max_hex = Lib.hexStringToByteArray("06");
      break;
    case 4:
      min_hex = Lib.hexStringToByteArray("07");
      max_hex = Lib.hexStringToByteArray("08");
      break;
    default:
      throw new RuntimeException("Illegal 'laser' value in Layer.power");
    }
    byte[] res = (byte[])ArrayUtils.addAll(c6, min_hex);
    write(data, (byte[])ArrayUtils.addAll(res, Lib.percentValueToByteArray(min_power)));
    res = (byte[])ArrayUtils.addAll(c6, max_hex);
    write(data, (byte[])ArrayUtils.addAll(res, Lib.percentValueToByteArray(max_power)));
  }

  /**
   * speed
   */
  private void speedC9(double speed) throws RuntimeException
  {
    write(data, (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("C902"), Lib.absValueToByteArray(speed)));
  }

  /**
   * speed (per layer)
   */
  private void layerSpeed(double speed) throws RuntimeException
  {
    System.out.println("layerSpeed(" + speed + ")");
    if (this.number == -1) {
      throw new RuntimeException("Layer.laserSpeed for frame layer");
    }
    byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("C904"), Lib.intValueToByteArray(this.number));
    write(data, (byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(speed)));
  }

  /**
   * blowOn
   * without, the laser does not turn off at the end of the job
   */
  private void blowOn()
  {
    writeHex(data, "ca0113");
  }

  /**
   * flagsCa01
   */
  private void flagsCa01(int flags)
  {
    writeHex(data, "ca01");
    write(data, Lib.intValueToByteArray(flags));    
  }

  /**
   * prio
   */
  private void prio(int prio)
  {
    writeHex(data, "ca02");
    write(data, Lib.intValueToByteArray(prio));    
  }

  private void dimensions(double top_left_x, double top_left_y, double bottom_right_x, double bottom_right_y)
  {
    if (this.number == -1) {
      /* overall dimensions */
      /**
       * Overall dimensions
       * Top_Left_E7_07 0.0mm 0.0mm                      e7 03 00 00 00 00 00 00 00 00 00 00 
       * Bottom_Right_E7_07 52.0mm 53.0mm                e7 07 00 00 03 16 20 00 00 03 1e 08 
       * Top_Left_E7_50 0.0mm 0.0mm                      e7 50 00 00 00 00 00 00 00 00 00 00 
       * Bottom_Right_E7_51 52.0mm 53.0mm                e7 51 00 00 03 16 20 00 00 03 1e 08 
       */
      byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("E703"), Lib.absValueToByteArray(top_left_x));
      write(data, (byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(top_left_y)));
      res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("E707"), Lib.absValueToByteArray(bottom_right_x));
      write(data, (byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(bottom_right_y)));
      res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("E750"), Lib.absValueToByteArray(top_left_x));
      write(data, (byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(top_left_y)));
      res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("E751"), Lib.absValueToByteArray(bottom_right_x));
      write(data, (byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(bottom_right_y)));
    }
    else {
      /* per-layer dimensions */
      /**
       * Layer dimensions
       * Layer_Top_Left_E7_52 Layer:0 0.0mm 0.0mm        e7 52 00 00 00 00 00 00 00 00 00 00 00 
       * Layer_Bottom_Right_E7_53 Layer:0 100.0mm 75.0mm e7 53 00 00 00 06 0d 20 00 00 04 49 78 
       * Layer_Top_Left_E7_61 Layer:0 0.0mm 0.0mm        e7 61 00 00 00 00 00 00 00 00 00 00 00 
       * Layer_Bottom_Right_E7_62 Layer:0 100.0mm 75.0mm e7 62 00 00 00 06 0d 20 00 00 04 49 78 
       */
      byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("E752"), Lib.intValueToByteArray(this.number));
      res = (byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(top_left_x));
      write(data, (byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(top_left_y)));
      res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("E753"), Lib.intValueToByteArray(this.number));
      res = (byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(bottom_right_x));
      write(data, (byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(bottom_right_y)));
      res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("E761"), Lib.intValueToByteArray(this.number));
      res = (byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(top_left_x));
      write(data, (byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(top_left_y)));
      res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("E762"), Lib.intValueToByteArray(this.number));
      res = (byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(bottom_right_x));
      write(data, (byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(bottom_right_y)));
    }
  }
  
  private void layerColor()
  {
    long color = this.red << 16 + this.green << 8 + this.blue;
    byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("ca06"), Lib.intValueToByteArray(this.number));
    write(data, (byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(color)));
  }
  private void layerCa41()
  {
    byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("ca41"), Lib.intValueToByteArray(this.number));
    write(data, (byte[])ArrayUtils.addAll(res, Lib.intValueToByteArray(0)));
  }
}
