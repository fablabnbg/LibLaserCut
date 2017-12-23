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

import com.t_oster.liblasercut.drivers.Ruida;
import com.t_oster.liblasercut.IllegalJobException;
import com.t_oster.liblasercut.JobPart;
import com.t_oster.liblasercut.LaserCutter;
import com.t_oster.liblasercut.LaserJob;
import com.t_oster.liblasercut.LaserProperty;
import com.t_oster.liblasercut.ProgressListener;
import com.t_oster.liblasercut.VectorCommand;
import com.t_oster.liblasercut.VectorPart;
import com.t_oster.liblasercut.platform.Util;
import org.apache.commons.lang3.ArrayUtils;
import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.Math;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;

/**
 * Support for ThunderLaser lasers, just vector cuts.
 * 
 *  Based on FullSpectrumCutter
 * 
 * @author Klaus Kämpf <kkaempf@suse.de>
 */

public class ThunderLaser extends LaserCutter
{
  
  protected static final String SETTING_FILE = "Output filename";
  protected static final String SETTING_MAX_VECTOR_CUT_SPEED = "Max vector cutting speed";
  protected static final String SETTING_MAX_VECTOR_MOVE_SPEED = "Max vector move speed";
  protected static final String SETTING_MAX_POWER = "Max laser power";
  protected static final String SETTING_BED_WIDTH = "Bed width (mm)";
  protected static final String SETTING_BED_HEIGHT = "Bed height (mm)";
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

  // TODO: use valid values, must be in steps ( 1000 steps = 1 inch)
  private static final int width = 0;
  private static final int height = 0;
  
  // https://stackoverflow.com/questions/11208479/how-do-i-initialize-a-byte-array-in-java
  public static byte[] hexStringToByteArray(String s) {
    int len = s.length();
    byte[] data = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
      data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                            + Character.digit(s.charAt(i+1), 16));
    }
    return data;
  }

  public ThunderLaser()
  {
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
    int power = 0;
    double speed = 100;
    double moving_speed = getMaxVectorMoveSpeed();
    
    pl.progressChanged(this, 0);
    pl.taskChanged(this, "checking job");
    checkJob(job);
    job.applyStartPoint();

    Ruida ruida = new Ruida();

    for (JobPart p : job.getParts())
    {
      //only accept VectorParts and add a warning for other parts.
      if (!(p instanceof VectorPart))
      {
        warnings.add("Non-vector parts are ignored by this driver.");
      }
      else
      {
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
              /**
               * Change speed or power.
               */
              LaserProperty prop = cmd.getProperty();
              for (String key : prop.getPropertyKeys())
              {
                String value = prop.getProperty(key).toString();
                if (key.equals("power"))
                  power = (int)Float.parseFloat(value);
                  ruida.setPower(power);
                if (key.equals("speed"))
                {
                  speed = Float.parseFloat(value);
                  speed = getMaxVectorCutSpeed() * speed / 100f; // to steps per sec
                  ruida.setSpeed(speed);
                }
              }
              break;
            }
          }
        }
      }
    }
    
    // connect to italk
    pl.taskChanged(this, "connecting");

    ruida.setFilename(getFilename());

    ruida.open();

    pl.taskChanged(this, "sending");

    ruida.write();

    pl.taskChanged(this, "closing");
    
    ruida.close();

    pl.progressChanged(this, 100);
  }

  
  /**
   * Waits for response from a BufferedInputStream and prints the response.
   * @param in
   * @throws IOException 
   */
  public void receiveResponse(BufferedInputStream in)throws IOException
  {
    byte[] inmsg = new byte[512];
    int n=0;
    inmsg[n] = (byte) in.read(); // TODO: set a timeout
    while(((in.available()) != 0)&(n<512))
    {
      n++;
      inmsg[n]=(byte)in.read();
    }
    System.out.println(new String(inmsg));
  }
  
  @Override
  public FloatPowerSpeedProperty getLaserPropertyForVectorPart() {
      return new FloatPowerSpeedProperty();
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

  protected Double BedWidth = 500d;
  /**
   * Returns the width of the laser-bed. 
   * @return 
   */
  @Override
  public double getBedWidth()
  {
    return (double)BedWidth;
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
  
  protected Double BedHeight = 300d;
  /**
   * Returns the height of the laser-bed. 
   * @return 
   */
  @Override
  public double getBedHeight()
  {
    return (double)BedHeight;
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
    return "ThunderLaser";
  }
  
  protected Integer LaserPowerMax = 180;
  
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
    this.LaserPowerMax = LaserPowerMax;
  }
  
  protected Integer MaxVectorCutSpeed = 1000;
  
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

  protected Integer MaxVectorMoveSpeed = 1000;
  
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
    SETTING_MAX_POWER,
    SETTING_BED_WIDTH,
    SETTING_BED_HEIGHT
  };
  
  @Override
  public Object getProperty(String attribute) {
    if (SETTING_FILE.equals(attribute)) {
      return this.getFilename();
    }else if (SETTING_MAX_VECTOR_CUT_SPEED.equals(attribute)){
      return this.getMaxVectorCutSpeed();}
    else if (SETTING_MAX_VECTOR_MOVE_SPEED.equals(attribute)){
      return this.getMaxVectorMoveSpeed();
    }else if (SETTING_MAX_POWER.equals(attribute)){
      return this.getLaserPowerMax();
    }else if (SETTING_BED_WIDTH.equals(attribute)){
      return this.getBedWidth();
    }else if (SETTING_BED_HEIGHT.equals(attribute)){
      return this.getBedHeight();
    } 
    return null;
  }
  
  @Override
  public void setProperty(String attribute, Object value) {
    if (SETTING_FILE.equals(attribute)) {
      this.setFilename((String) value);
    }
    else if (SETTING_MAX_VECTOR_CUT_SPEED.equals(attribute)) {
      this.setMaxVectorCutSpeed((Integer) value);
    }
    else if (SETTING_MAX_VECTOR_MOVE_SPEED.equals(attribute)) {
      this.setMaxVectorMoveSpeed((Integer) value);
    }
    else if (SETTING_MAX_POWER.equals(attribute)) {
      this.setLaserPowerMax((Integer) value);
    }
    else if (SETTING_BED_HEIGHT.equals(attribute)) {
      this.setBedHeigth((Double) value);
    }
    else if (SETTING_BED_WIDTH.equals(attribute)) {
      this.setBedWidth((Double) value);
    } 
  }
  
  @Override
  public String[] getPropertyKeys() {
    return settingAttributes;
  }
  
}
