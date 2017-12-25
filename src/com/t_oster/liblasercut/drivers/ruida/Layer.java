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
 * Represents a set of vectors with the same power, frequency, and speed
 *
 */

public class Layer
{
  private int number;
  /* layer dimensions */
  private double top_left_x = 0.0;
  private double top_left_y = 0.0;
  private double bottom_right_x = 0.0;
  private double bottom_right_y = 0.0;
  /* layer properties */
  private double frequency = 0.0;
  private double focus = 0.0;
  private int red = 0;
  private int green = 0;
  private int blue = 0;
  /* internal buffer of vector cmds */
  private ByteArrayOutputStream vectors;
  private double travel_distance;
  private double xsim = 0.0;
  private double ysim = 0.0;
  private double max_x = 0.0;
  private double max_y = 0.0;

  public Layer(int number)
  {
    this.vectors = new ByteArrayOutputStream();
    this.number = number;
    blowOn();
    writeHex("ca030f");
    writeHex("ca1000");
  }
  public void setDimensions(double top_left_x, double top_left_y, double bottom_right_x, double bottom_right_y)
  {
    System.out.println("Layer.dimensions(" + top_left_x + ", " + top_left_y + ", " + bottom_right_x + ", " + bottom_right_y + ")");
    this.top_left_x = top_left_x;
    this.top_left_y = top_left_y;
    this.bottom_right_x = bottom_right_x;
    this.bottom_right_y = bottom_right_y;
  }
  /**
   * property getters
   */
  public double getTopLeftX()
  {
    return this.top_left_x;
  }
  public double getTopLeftY()
  {
    return this.top_left_y;
  }
  public double getBottomRightX()
  {
    return this.bottom_right_x;
  }
  public double getBottomRightY()
  {
    return this.bottom_right_y;
  }
  /**
   * vector
   * 
   * compute max_x, max_y, and travel_distance
   * 
   */

  public void vectorTo(double x, double y, boolean as_move)
  {
    double dx = x - xsim;
    double dy = y - ysim;

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
    return this.travel_distance;
  }

  /**
   * write vectors as layer to out
   *
   */
  public void writeTo(OutputStream out) throws IOException
  {
    System.out.println("Layer.writeLayerTo(" + this.number + ") " + vectors.size() + " vector bytes");
    
    vectors.writeTo(out);
  }

  /**
   * property setters
   */

  public void setSpeed(double speed)
  {
    System.out.println("Layer.setSpeed(" + speed + ")");
    layerSpeed(speed);
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
    layerLaserPower(this.number, 1, min_power, max_power);
    layerLaserPower(this.number, 2, 18, 30);
    layerLaserPower(this.number, 3, 30, 30);
    layerLaserPower(this.number, 4, 30, 30);
  }
  /**
   * set RGB for preview display
   */
  public void setRGB(int red, int green, int blue)
  {
    this.red = red;
    this.green = green;
    this.blue = blue;
  }

/*-----------------------------------------------------------------*/

  /**
   * scramble & write vector data
   */
  private void write(byte[] data)
  {
    try {
      vectors.write(Lib.scramble(data));
    } catch (IOException ex) {
      System.out.println("IOException in ruida.Layer.write");
    }
  }

  /**
   * scramble & write hex string
   */
  private void writeHex(String hex)
  {
    write(Lib.hexStringToByteArray(hex));
  }

  /**
   * Move absolute
   */
  private void moveAbs(double x, double y)
  {
//    System.out.println("moveAbs(" + x + ", " + y + ")");
    byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("88"), Lib.absValueToByteArray(x));
    write((byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(y)));
  }

  /**
   * Move relative
   */
  private void moveRel(double x, double y)
  {
//    System.out.println("moveRel(" + x + ", " + y + ")");
    byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("89"), Lib.relValueToByteArray(x));
    write((byte[])ArrayUtils.addAll(res, Lib.relValueToByteArray(y)));
  }

  /**
   * Move relative horizontal
   */
  private void moveHoriz(double x)
  {
//    System.out.println("moveHoriz(" + x + ")");
    byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("8A"), Lib.relValueToByteArray(x));
    write(res);
  }

  /**
   * Move relative vertical
   */
  private void moveVert(double y)
  {
//    System.out.println("moveVert(" + y + ")");
    byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("8B"), Lib.relValueToByteArray(y));
    write(res);
  }

  /**
   * Cut relative horizontal
   */
  private void cutHoriz(double x)
  {
//    System.out.println("cutHoriz(" + x + ")");
    byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("AA"), Lib.relValueToByteArray(x));
    write(res);
  }

  /**
   * Cut relative vertical
   */
  private void cutVert(double y)
  {
//    System.out.println("cutVert(" + y + ")");
    byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("AB"), Lib.relValueToByteArray(y));
    write(res);
  }

  /**
   * Cut relative
   */
  private void cutRel(double x, double y)
  {
//    System.out.println("cutRel(" + x + ", " + y + ")");
    byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("A9"), Lib.relValueToByteArray(x));
    write((byte[])ArrayUtils.addAll(res, Lib.relValueToByteArray(y)));
  }

  /**
   * Cut absolute
   */
  private void cutAbs(double x, double y)
  {
//    System.out.println("cutAbs(" + x + ", " + y + ")");
    byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("A8"), Lib.absValueToByteArray(x));
    write((byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(y)));
  }

  /**
   * power (per laser)
   */
  private void layerLaserPower(int layer, int laser, int min_power, int max_power) throws RuntimeException
  {
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
      throw new RuntimeException("Illegal 'laser' value in Ruida.layerLaserPower");
    }
    byte[] res = (byte[])ArrayUtils.addAll(c6, min_hex);
    res = (byte[])ArrayUtils.addAll(res, Lib.intValueToByteArray(this.number));
    write((byte[])ArrayUtils.addAll(res, Lib.percentValueToByteArray(min_power)));
    res = (byte[])ArrayUtils.addAll(c6, max_hex);
    res = (byte[])ArrayUtils.addAll(res, Lib.intValueToByteArray(this.number));
    write((byte[])ArrayUtils.addAll(res, Lib.percentValueToByteArray(max_power)));
  }

  /**
   * speed (per layer)
   */
  private void layerSpeed(double speed)
  {
    System.out.println("layerSpeed(" + speed + ")");
    byte[] res = (byte[])ArrayUtils.addAll(Lib.hexStringToByteArray("C904"), Lib.intValueToByteArray(this.number));
    write((byte[])ArrayUtils.addAll(res, Lib.absValueToByteArray(speed)));
  }

  /**
   * blowOn
   * without, the laser does not turn off at the end of the job
   */
  private void blowOn()
  {
    writeHex("ca0113");
  }

}
