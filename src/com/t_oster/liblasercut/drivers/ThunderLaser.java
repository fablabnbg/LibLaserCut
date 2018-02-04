/**
 * This file is part of LibLaserCut.
 *
 * Support for ThunderLaser lasers, just vector cuts.
 *
 * @author Klaus Kämpf <kkaempf@suse.de>
 * Copyright (C) 2017,2018 Klaus Kämpf <kkaempf@suse.de>
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

import com.t_oster.liblasercut.*;
import com.t_oster.liblasercut.drivers.ThunderLaserProperty;
import com.t_oster.liblasercut.drivers.ruida.*;
import com.t_oster.liblasercut.platform.Point;
import com.t_oster.liblasercut.platform.Util;
import org.apache.commons.lang3.ArrayUtils;
import java.io.BufferedReader;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.Math;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ThunderLaser extends LaserCutter
{

  private static final int MINFOCUS = -500; //Minimal focus value (not mm)
  private static final int MAXFOCUS = 500; //Maximal focus value (not mm)
  private static final int MAXPOWER = 70;
  private static final double FOCUSWIDTH = 0.0252; //How much mm/unit the focus values are
  protected static final String SETTING_FILE = "Output filename";
  protected static final String SETTING_MAX_VECTOR_CUT_SPEED = "Max vector cutting speed (mm/s)";
  protected static final String SETTING_MAX_VECTOR_MOVE_SPEED = "Max vector move speed (mm/s)";
  protected static final String SETTING_MIN_POWER = "Min laser power (%)";
  protected static final String SETTING_MAX_POWER = "Max laser power (%)";
  protected static final String SETTING_BED_WIDTH = "Bed width (mm)";
  protected static final String SETTING_BED_HEIGHT = "Bed height (mm)";
  protected static final String SETTING_USE_BIDIRECTIONAL_RASTERING = "Use bidirectional rastering";
  // config values
  private static final long[] JogAcceleration = {200000,50000,600000};
  private static final long[] JogMaxVelocity = {16,16,2048};
  private static final long[] EngraveAcceleration = {200000,50000,600000};
  private static final long[] EngraveMaxVelocity = {800,800,2048};
  private static final long[] VectorAcceleration = {100000,25000,20000};
  private static final long[] VectorMaxVelocity = {1000,1000,1000};
  private static final byte FlipLaserPWMPower = 1;
  private static final byte FlipLaserOutput = 0;

  private static final byte HomeDirection = 1;
  private static final byte[] FlipHomeDirection = {1,0,0};
  private static final byte[] LimitContCondition = {0,0,0,0};
  private static final long[] MaxSteps = {250,500,500};
  private static final long[] TableSize = {20000,12000,30000};

  private int mm2focus(float mm)
  {
    return (int) (mm / FOCUSWIDTH);
  }

  private float focus2mm(int focus)
  {
    return (float) (focus * FOCUSWIDTH);
  }

  private Ruida ruida;

  public ThunderLaser()
  {
    System.out.println("ThunderLaser()");
    ruida = new Ruida();
    ruida.setFilename(getFilename());
  }

  @Override
  public ThunderLaserProperty getLaserPropertyForVectorPart()
  {
    return new ThunderLaserProperty();
  }

  @Override
  public ThunderLaserProperty getLaserPropertyForRasterPart()
  {
    return new ThunderLaserProperty();
  }

  @Override
  public ThunderLaserProperty getLaserPropertyForRaster3dPart()
  {
    return new ThunderLaserProperty();
  }

  @Override
  public boolean canEstimateJobDuration()
  {
    return true;
  }

  /**
   * When rastering, whether to always cut from left to right, or to cut in both
   * directions? (i.e. use the return stroke to raster as well)
   */
  protected boolean useBidirectionalRastering = false;

  public boolean getUseBidirectionalRastering()
  {
    return useBidirectionalRastering;
  }

  public void setUseBidirectionalRastering(boolean useBidirectionalRastering)
  {
    this.useBidirectionalRastering = useBidirectionalRastering;
  }

  /*
   * estimateJobDuration - copied from EpilogCutter
   */

  @Override
  public int estimateJobDuration(LaserJob job)
  {
    double VECTOR_MOVESPEED_X = 20000d / 4.5;
    double VECTOR_MOVESPEED_Y = 10000d / 2.5;
    double VECTOR_LINESPEED = 20000d / 36.8;
    double RASTER_LINEOFFSET = 0.08d;
    double RASTER_LINESPEED = 100000d / ((268d / 50) - RASTER_LINEOFFSET);
    //TODO: The Raster3d values are not tested yet, theyre just copies
    double RASTER3D_LINEOFFSET = 0.08;
    double RASTER3D_LINESPEED = 100000d / ((268d / 50) - RASTER3D_LINEOFFSET);

    //Holds the current Laser Head position in Pixels
    Point p = new Point(0, 0);

    double result = 0;//usual offset
    for (JobPart jp : job.getParts())
    {
      if (jp instanceof RasterPart)
      {
        RasterPart rp = (RasterPart) jp;
        Point sp = rp.getRasterStart();
        result += Math.max((double) (p.x - sp.x) / VECTOR_MOVESPEED_X,
          (double) (p.y - sp.y) / VECTOR_MOVESPEED_Y);
        double linespeed = ((double) RASTER_LINESPEED * ((ThunderLaserProperty) rp.getLaserProperty()).getSpeed()) / 100;
        ByteArrayList line = new ByteArrayList(rp.getRasterWidth());
        for (int y = 0; y < rp.getRasterHeight(); y++)
        {//Find any black point
          boolean lineEmpty = true;
	  rp.getRasterLine(y, line);
          for (byte b : line)
          {
            if (b != 0)
            {
              lineEmpty = false;
              break;
            }
          }
          if (!lineEmpty)
          {
            int w = rp.getRasterWidth();
            result += (double) RASTER_LINEOFFSET + (double) w / linespeed;
            p.x = sp.y % 2 == 0 ? sp.x + w : sp.x;
            p.y = sp.y + y;
          }
          else
          {
            result += RASTER_LINEOFFSET;
          }
        }
      }
      if (jp instanceof Raster3dPart)
      {
        Raster3dPart rp = (Raster3dPart) jp;
        Point sp = rp.getRasterStart();
        result += Math.max((double) (p.x - sp.x) / VECTOR_MOVESPEED_X,
          (double) (p.y - sp.y) / VECTOR_MOVESPEED_Y);
        double linespeed = ((double) RASTER3D_LINESPEED * ((ThunderLaserProperty) rp.getLaserProperty()).getSpeed()) / 100;
	ByteArrayList line = new ByteArrayList(rp.getRasterWidth());
        for (int y = 0; y < rp.getRasterHeight(); y++)
        {//Check if
          boolean lineEmpty = true;
	  rp.getRasterLine(y, line);
          for (byte b : line)
          {
            if (b != 0)
            {
              lineEmpty = false;
              break;
            }
          }
          if (!lineEmpty)
          {
            int w = rp.getRasterWidth();
            result += (double) RASTER3D_LINEOFFSET + (double) w / linespeed;
            p.x = sp.y % 2 == 0 ? sp.x + w : sp.x;
            p.y = sp.y + y;
          }
        }
      }
      if (jp instanceof VectorPart)
      {
        double speed = VECTOR_LINESPEED;
        VectorPart vp = (VectorPart) jp;
        for (VectorCommand cmd : vp.getCommandList())
        {
          switch (cmd.getType())
          {
            case SETPROPERTY:
            {
              speed = VECTOR_LINESPEED * ((ThunderLaserProperty) cmd.getProperty()).getSpeed() / 100;
              break;
            }
            case MOVETO:
              result += Math.max((double) (p.x - cmd.getX()) / VECTOR_MOVESPEED_X,
                (double) (p.y - cmd.getY()) / VECTOR_MOVESPEED_Y);
              p = new Point(cmd.getX(), cmd.getY());
              break;
            case LINETO:
              double dist = distance(cmd.getX(), cmd.getY(), p);
              p = new Point(cmd.getX(), cmd.getY());
              result += dist / speed;
              break;
          }
        }
      }
    }
    return (int) result;
  }

  private double distance(int x, int y, Point p)
  {
    return Math.sqrt(Math.pow(p.x - x, 2) + Math.pow(p.y - y, 2));
  }


  /*
   * checkJob - copied from EpilogCutter
   */

  @Override
  protected void checkJob(LaserJob job) throws IllegalJobException
  {
    super.checkJob(job);
    for (JobPart p : job.getParts())
    {
      if (p instanceof VectorPart)
      {
        for (VectorCommand cmd : ((VectorPart) p).getCommandList())
        {
          if (cmd.getType() == VectorCommand.CmdType.SETPROPERTY)
          {
            if (!(cmd.getProperty() instanceof ThunderLaserProperty))
            {
              throw new IllegalJobException("This driver expects Min Power, Power, Speed, Frequency, and Focus as settings");
            }
            float focus = ((ThunderLaserProperty) cmd.getProperty()).getFocus();
            if (mm2focus(focus) > MAXFOCUS || (mm2focus(focus)) < MINFOCUS)
            {
              throw new IllegalJobException("Illegal Focus value. This Lasercutter supports values between"
                + focus2mm(MINFOCUS) + "mm to " + focus2mm(MAXFOCUS) + "mm.");
            }
          }
        }
      }
      if (p instanceof RasterPart)
      {
        RasterPart rp = ((RasterPart) p);
        float focus = rp.getLaserProperty() == null ? 0 : ((ThunderLaserProperty)rp.getLaserProperty()).getFocus();
        if (mm2focus(focus) > MAXFOCUS || (mm2focus(focus)) < MINFOCUS)
        {
          throw new IllegalJobException("Illegal Focus value. This Lasercutter supports values between"
            + focus2mm(MINFOCUS) + "mm to " + focus2mm(MAXFOCUS) + "mm.");
        }
      }
      if (p instanceof Raster3dPart)
      {
        Raster3dPart rp = (Raster3dPart) p;
        float focus = rp.getLaserProperty() == null ? 0 : ((ThunderLaserProperty)rp.getLaserProperty()).getFocus();
        if (mm2focus(focus) > MAXFOCUS || (mm2focus(focus)) < MINFOCUS)
        {
          throw new IllegalJobException("Illegal Focus value. This Lasercutter supports values between"
            + focus2mm(MINFOCUS) + "mm to " + focus2mm(MAXFOCUS) + "mm.");
        }
      }
    }
  }

  /**
   * It is called, whenever VisiCut wants the driver to send a job to the lasercutter.
   * @param job This is an LaserJob object, containing all information on the job, which is to be sent
   * @param pl Use this object to inform VisiCut about the progress of your sending action.
   * @param warnings If you there are warnings for the user, you can add them to this list, so they can be displayed by VisiCut
   * @throws IllegalJobException Throw this exception, when the job is not suitable for the current machine
   * @throws Exception
   */
  @Override
  public void sendJob(LaserJob job, ProgressListener pl, List<String> warnings) throws IllegalJobException, Exception
  {
    System.out.println("JOB title >" + job.getTitle() + "< name >" + job.getName() + "< user >"+ job.getUser() + "<");

    pl.progressChanged(this, 0);
    pl.taskChanged(this, "checking job");
    checkJob(job);
    job.applyStartPoint();

    ruida.setName(job.getTitle());

    for (JobPart p : job.getParts())
    {
      float focus;

      if (p instanceof Raster3dPart || p instanceof RasterPart)
      {
        RasterPart rp = (RasterPart)p;
        double dpi = rp.getDPI();
        Point sp = rp.getRasterStart();
        int width = rp.getRasterWidth();
        int height = rp.getRasterHeight();
        System.out.println(String.format("RasterPart(%d x %d pixels) @ %.1f dpi", width, height, dpi));
        double rwidth = Util.px2mm(width, dpi);
        double rheight = Util.px2mm(height, dpi);

        // ruida x,y in mm
        double rx = Util.px2mm(sp.x, dpi);
        double ry = Util.px2mm(sp.y, dpi);
        System.out.println(String.format("RasterPart(%.4fm, %.4f mm) - (%.4f, %.4f mm)", rx, ry, rwidth, rheight));
        ruida.startPart(rx, ry, rwidth, rheight);

        ThunderLaserProperty prop = (ThunderLaserProperty) rp.getLaserProperty();
        ruida.setMinPower(prop.getMinPower());
        ruida.setMaxPower((int)prop.getPower());
        ruida.setSpeed((int)prop.getSpeed());
        ruida.setFrequency((int)prop.getFrequency());
        // focus ?

        boolean leftToRight = true;
        for (int y = 0; y < height; y++) {
          // FIXME: change firstNonWhitePixel to nextColorChange inside loop
          //   to have only one lineTo/moveTo statement
          int x = rp.firstNonWhitePixel(y);
//          System.out.println(String.format("Line %d, %s: first-non-white at %d", y, (leftToRight?"left-to-right":"right-to-left"), x));
          if (leftToRight && (x >= width)) {
            continue;
          }
          if ((!leftToRight) && (x <= 0)) {
            continue;
          }
          rx = Util.px2mm(sp.x + x, dpi);
          ry = Util.px2mm(sp.y + y, dpi);
          ruida.moveTo(rx, ry);
//          System.out.println(String.format("Line %d starts at %.2f, %.2f", y, rx, ry));
          boolean colorIsBlack = true; // rx is a non-white pixel
          for (;;) {
            x = rp.nextColorChange(x,y);
//            System.out.println(String.format("Line %d next color change @ %d", y, x));
            if (leftToRight) {
              if (x >= width) {
                break;
              }
            }
            else {
              if (x <= 0) {
                break;
              }
            }
            rx = Util.px2mm(sp.x + x, dpi);
            ry = Util.px2mm(sp.y + y, dpi);
//            System.out.println(String.format("Next %s point @ %.2f, %.2f mm", (colorIsBlack?"black":"white"), rx, ry));
            if (colorIsBlack) {
              ruida.lineTo(rx, ry);
            }
            else {
              ruida.moveTo(rx, ry);
            }
            colorIsBlack = !colorIsBlack;
          }
          if (this.useBidirectionalRastering) {
            leftToRight = !leftToRight;
            rp.toggleRasteringCutDirection();
          }
        }
      }
      else if (p instanceof VectorPart)
      {
        double minX = Util.px2mm(p.getMinX(), p.getDPI());
        double minY = Util.px2mm(p.getMinY(), p.getDPI());
        double maxX = Util.px2mm(p.getMaxX(), p.getDPI());
        double maxY = Util.px2mm(p.getMaxY(), p.getDPI());

        ruida.startPart(minX, minY, maxX, maxY);

        System.out.println("VectorPart(" + minX + ", " + minY + ", " + maxX + ", " + maxY + " @ " + p.getDPI() + "dpi)");
        //get the real interface
        VectorPart vp = (VectorPart) p;
        //iterate over command list
        for (VectorCommand cmd : vp.getCommandList())
        {
          //There are three types of commands: MOVETO, LINETO and SETPROPERTY
          switch (cmd.getType())
          {
            case LINETO:
            {
              /**
               * Move the laserhead (laser on) from the current position to the x/y position of this command.
               */
              double x = Util.px2mm(cmd.getX(), p.getDPI());
              double y = Util.px2mm(cmd.getY(), p.getDPI());
              ruida.lineTo(x, y);
              break;
            }
            case MOVETO:
            {
              /**
               * Move the laserhead (laser off) from the current position to the x/y position of this command.
               */
              double x = Util.px2mm(cmd.getX(), p.getDPI());
              double y = Util.px2mm(cmd.getY(), p.getDPI());
              ruida.moveTo(x, y);
              break;
            }
            case SETPROPERTY:
            {
              /*
               * "Min Power(%)", "Max Power(%)", "Speed(mm/s)", "Focus(mm)", "Frequency(Hz)"
               */
              LaserProperty prop = cmd.getProperty();
              for (String key : prop.getPropertyKeys())
              {
                String value = prop.getProperty(key).toString();
                if (key.equals("Min Power(%)"))
                {
                  float power = Float.parseFloat(value);
                  if (power > MAXPOWER) {
                    power = MAXPOWER;
                  }
                  else if (power < 0) {
                    power = 0;
                  }
                  ruida.setMinPower((int)power);
                }
                else if (key.equals("Max Power(%)"))
                {
                  float power = Float.parseFloat(value);
                  if (power > MAXPOWER) {
                    power = MAXPOWER;
                  }
                  else if (power < 0) {
                    power = 0;
                  }
                  ruida.setMaxPower((int)power);
                }
                else if (key.equals("Speed(mm/s)"))
                {
                  float speed = Float.parseFloat(value);
                  ruida.setSpeed((int)speed);
                }
                else if (key.equals("Focus(mm)"))
                {
                  focus = Float.parseFloat(value);
                  ruida.setFocus(focus);
                }
                else if (key.equals("Frequency(Hz)"))
                {
                  float frequency = Float.parseFloat(value);
                  ruida.setFrequency((int)frequency);
                }
                else
                {
                  System.out.println("*** ThunderLaser unknown key(" + key + ")");
                }
              }
              break;
            }
            default:
            {
              System.out.println("*** ThunderLaser unknown vector part(" + cmd.getType() + ")");
            }
          }
        }
      }
      else
      {
        warnings.add("Unknown Job part.");
      }
      ruida.endPart();
    }

    // connect to italk
    pl.taskChanged(this, "connecting");

    ruida.setFilename(getFilename());

    try {
      ruida.open();

      pl.taskChanged(this, "sending");

      ruida.write();

      pl.taskChanged(this, "closing");

      ruida.close();
    }
    catch (Exception e) {
      pl.taskChanged(this, "Fail: " + e.getMessage());
    }
    pl.progressChanged(this, 100);
  }

  /**
   * extract focus from LaserProperty (coming from RasterPart or Raster3dPart)
   */
  private float extractFocus(LaserProperty lp) throws IllegalJobException
  {
    float focus = 0;
    if (lp == null)
    {
      return focus;
    }
    if (lp instanceof ThunderLaserProperty)
    {
      focus = ((ThunderLaserProperty)lp).getFocus();
      if (mm2focus(focus) > MAXFOCUS || (mm2focus(focus)) < MINFOCUS)
      {
        throw new IllegalJobException("Illegal Focus value. This Lasercutter supports values between"
                                      + focus2mm(MINFOCUS) + "mm to " + focus2mm(MAXFOCUS) + "mm.");
      }
    }
    else
    {
      throw new IllegalJobException("This driver expects Power, Speed, and Focus as settings");
    }
    return focus;
  }

  /**
   * Returns a list of all supported resolutions (in DPI)
   * @return
   */
  @Override
  public List<Double> getResolutions()
  {
    return Arrays.asList(new Double[]{100.0,200.0,500.0,1000.0});
  }

  protected Double BedWidth = 0d;
  /**
   * Returns the width of the laser-bed in mm.
   * @return
   */
  @Override
  public double getBedWidth()
  {
    if (BedWidth > 0) { // value set before
      return BedWidth;
    }
    else {
      try {
        return ruida.getBedWidth();
      }
      catch (Exception e) {
      }
    }
    return 900.0;
  }

  /**
   * Set the value of BedWidth
   *
   * @param BedWidth new value of BedWidth
   */
  public void setBedWidth(Double BedWidth)
  {
    this.BedWidth = BedWidth;
  }

  protected Double BedHeight = 0d;
  /**
   * Returns the height of the laser-bed in mm.
   * @return
   */
  @Override
  public double getBedHeight()
  {
    if (BedHeight > 0) { // value set before
      return BedHeight;
    }
    else {
      try {
        return ruida.getBedHeight();
      }
      catch (Exception e) {
      }
    }
    return 600.0;
  }

  /**
   * Set the value of BedHeigth
   *
   * @param BedHeight new value of BedHeight
   */
  public void setBedHeigth(Double BedHeight)
  {
    this.BedHeight = BedHeight;
  }

  /**
   * Get the name for this driver.
   *
   * @return the name for this driver
   */
  @Override
  public String getModelName()
  {
    return "Thunderlaser";
  }

  protected Integer LaserPowerMin = 0;

  /**
   * Get the value of LaserPowerMin
   *
   * @return the value of LaserPowerMin
   */
  public Integer getLaserPowerMin()
  {
    return LaserPowerMin;
  }

  /**
   * Set the value of LaserPowerMin
   *
   * @param LaserPowerMin new value of LaserPowerMin
   */
  public void setLaserPowerMin(Integer LaserPowerMin)
  {
    this.LaserPowerMin = LaserPowerMin;
  }

  protected Integer LaserPowerMax = MAXPOWER;

  /**
   * Get the value of LaserPowerMax
   *
   * @return the value of LaserPowerMax
   */
  public Integer getLaserPowerMax()
  {
    return LaserPowerMax;
  }

  /**
   * Set the value of LaserPowerMax
   *
   * @param LaserPowerMax new value of LaserPowerMax
   */
  public void setLaserPowerMax(Integer LaserPowerMax)
  {
    if (LaserPowerMax > MAXPOWER) {
      LaserPowerMax = MAXPOWER;
    }
    this.LaserPowerMax = LaserPowerMax;
  }

  protected Integer MaxVectorCutSpeed = 10000;

  /**
   * Get the value of MaxVectorCutSpeed
   *
   * @return the value of Maximum Vector Cut Speed
   */
  public Integer getMaxVectorCutSpeed()
  {
    return MaxVectorCutSpeed;
  }

  /**
   * Set the value of MaxVectorCutSpeed
   *
   * @param MaxVectorCutSpeed new value of MaxVectorCutSpeed
   */
  public void setMaxVectorCutSpeed(Integer MaxVectorCutSpeed)
  {
    this.MaxVectorCutSpeed = MaxVectorCutSpeed;
  }

  protected Integer MaxVectorMoveSpeed = 10000;

  /**
   * Get the value of MaxVectorMoveSpeed
   *
   * @return the value of Vector Moving Speed
   */
  public Integer getMaxVectorMoveSpeed()
  {
    return MaxVectorMoveSpeed;
  }

  /**
   * Set the value of MaxVectorMoveSpeed
   *
   * @param MaxVectorMoveSpeed new value of MaxVectorMoveSpeed
   */
  public void setMaxVectorMoveSpeed(Integer MaxVectorMoveSpeed)
  {
    this.MaxVectorMoveSpeed = MaxVectorMoveSpeed;
  }

  protected String filename = "thunder.rd";

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
   * Copies the current instance with all config settings, because
   * it is used for save- and restoring
   * @return
   */
  @Override
  public ThunderLaser clone() {
    ThunderLaser clone = new ThunderLaser();
    clone.copyProperties(this);
    return clone;
  }

  private static String[] settingAttributes = new String[]
  {
    SETTING_FILE,
    SETTING_MAX_VECTOR_CUT_SPEED,
    SETTING_MAX_VECTOR_MOVE_SPEED,
    SETTING_MIN_POWER,
    SETTING_MAX_POWER,
    SETTING_BED_WIDTH,
    SETTING_BED_HEIGHT,
    SETTING_USE_BIDIRECTIONAL_RASTERING
  };

  @Override
  public Object getProperty(String attribute) {
    if (SETTING_FILE.equals(attribute)) {
      return this.getFilename();
    }
    else if (SETTING_MAX_VECTOR_CUT_SPEED.equals(attribute)) {
      return this.getMaxVectorCutSpeed();
    }
    else if (SETTING_MAX_VECTOR_MOVE_SPEED.equals(attribute)) {
      return this.getMaxVectorMoveSpeed();
    }
    else if (SETTING_MIN_POWER.equals(attribute)) {
      return this.getLaserPowerMin();
    }
    else if (SETTING_MAX_POWER.equals(attribute)) {
      return this.getLaserPowerMax();
    }
    else if (SETTING_BED_WIDTH.equals(attribute)) {
      return this.getBedWidth();
    }
    else if (SETTING_BED_HEIGHT.equals(attribute)) {
      return this.getBedHeight();
    }
    else if (SETTING_USE_BIDIRECTIONAL_RASTERING.equals(attribute)) {
      return this.getUseBidirectionalRastering();
    }
    return null;
  }

  @Override
  public void setProperty(String attribute, Object value) {
    if (SETTING_FILE.equals(attribute)) {
      this.setFilename((String) value);
    }
    else if (SETTING_MAX_VECTOR_CUT_SPEED.equals(attribute)) {
      this.setMaxVectorCutSpeed((Integer)value);
    }
    else if (SETTING_MAX_VECTOR_MOVE_SPEED.equals(attribute)) {
      this.setMaxVectorMoveSpeed((Integer)value);
    }
    else if (SETTING_MIN_POWER.equals(attribute)) {
      this.setLaserPowerMin((Integer)value);
    }
    else if (SETTING_MAX_POWER.equals(attribute)) {
      this.setLaserPowerMax((Integer)value);
    }
    else if (SETTING_BED_HEIGHT.equals(attribute)) {
      this.setBedHeigth((Double)value);
    }
    else if (SETTING_BED_WIDTH.equals(attribute)) {
      this.setBedWidth((Double)value);
    }
    else if (SETTING_USE_BIDIRECTIONAL_RASTERING.equals(attribute)) {
      this.setUseBidirectionalRastering((Boolean) value);
    }
  }

  @Override
  public String[] getPropertyKeys() {
    return settingAttributes;
  }

}
