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
/*
 * Copyright (C) 2016 Jürgen Weigert <juewei@fabfolk.com>
 */
package com.t_oster.liblasercut.drivers;

import com.t_oster.liblasercut.*;
import com.t_oster.liblasercut.platform.Point;
import com.t_oster.liblasercut.platform.Util;
// import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.File;
import java.nio.channels.FileChannel;
import java.io.FileDescriptor;
import java.io.FileInputStream;
// import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.*;
import purejavacomm.CommPort;
import purejavacomm.CommPortIdentifier;
import purejavacomm.SerialPort;
import java.net.URI;

/**
 * This class implements a driver for the Silhouette Cameo
 *
 * @author Jürgen Weigert <juewei@fabfolk.com>
 */
public class SilhouetteCameo extends LaserCutter {

  private static final String SETTING_DEVPORT = "USB-Port/Device (/dev/usb/lp0 or file:///tmp/out.cameo)";
  private static final String SETTING_BEDWIDTH = "Cutter width [mm]";
  private static final String SETTING_BEDHEIGHT = "Max. media length [mm]";
  private static final String SETTING_HARDWARE_DPI = "Cutter resolution [steps/inch]";
  private static final String SETTING_RASTER_WHITESPACE = "Additional space per raster line (mm)";

  protected int hw_x = 0;
  protected int hw_y = 0;
  protected RandomAccessFile devr;

  @Override
  public String getModelName() {
    return "SilhouetteCameo";
  }
  private double addSpacePerRasterLine = 0.5;

  /**
   * Get the value of addSpacePerRasterLine
   *
   * @return the value of addSpacePerRasterLine
   */
  public double getAddSpacePerRasterLine() {
    return addSpacePerRasterLine;
  }

  /**
   * Set the value of addSpacePerRasterLine
   *
   * @param addSpacePerRasterLine new value of addSpacePerRasterLine
   */
  public void setAddSpacePerRasterLine(double addSpacePerRasterLine) {
    this.addSpacePerRasterLine = addSpacePerRasterLine;
  }

  // protected String devPort = "usb://0b4d:1121/";	// VendorId:ProductID
  protected String devPort = "/dev/usb/lp0";
  /**
   * Get the value of port
   *
   * @return the value of port
   */
  public String getDevPort() {
    return devPort;
  }
  /**
   * Set the value of port
   *
   * @param devPort new value of port
   */
  public void setDevPort(String devPort) {
    this.devPort = devPort;
  }

  private byte[] generateVectorGCode(VectorPart vp, double resolution) throws UnsupportedEncodingException {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(result, true, "US-ASCII");
    for (VectorCommand cmd : vp.getCommandList()) {
      switch (cmd.getType()) {
        case MOVETO:
          int x = cmd.getX();
          int y = cmd.getY();
          move(out, x, y, resolution);
          break;
        case LINETO:
          x = cmd.getX();
          y = cmd.getY();
          line(out, x, y, resolution);
          break;
        case SETPROPERTY:
          PowerSpeedFocusFrequencyProperty p = (PowerSpeedFocusFrequencyProperty) cmd.getProperty();
          setPressure(p.getPower());
          setSpeed(p.getSpeed());
          break;
      }
    }
    return result.toByteArray();
  }
  private int currentPressure = -1;	// pressure 1..33
  private int currentSpeed = -1;	// speed 1..10
  private int currentKnive = -1;	// 0=pen, 18=knive

  private void setSpeed(int newval) {
    if (newval != currentSpeed) {
      if (newval < 1) newval = 1;
      if (newval > 10) newval = 10;
      currentSpeed = newval;
    }
  }

  private void setKnive(int newval) {
    if (newval != currentKnive) {
      if (newval < 10) newval = 0;
      if (newval >= 10) newval = 18;
      currentKnive = newval;
    }
  }

  private void setPressure(int newval) {
    if (newval != currentPressure) {
      if (newval < 1) newval = 1;
      if (newval > 33) newval = 33;
      currentPressure = newval;
    }
  }

  private void move(PrintStream out, int x, int y, double resolution) {
    double hw_scale = this.getHwDPI()/resolution;
    hw_x = (int)(hw_scale * x);
    hw_y = (int)(hw_scale * y);
    out.printf(Locale.US, "M%.2f,%.2f;", hw_x, hw_y);
  }

  private void line(PrintStream out, int x, int y, double resolution) {
    double hw_scale = this.getHwDPI()/resolution;
    hw_x = (int)(hw_scale * x);
    hw_y = (int)(hw_scale * y);
    out.printf(Locale.US, "D%.2f,%.2f;", hw_x, hw_y);
  }

  private byte[] generatePseudoRaster3dGCode(Raster3dPart rp, double resolution) throws UnsupportedEncodingException {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(result, true, "US-ASCII");
    boolean dirRight = true;
    Point rasterStart = rp.getRasterStart();
    PowerSpeedFocusProperty prop = (PowerSpeedFocusProperty) rp.getLaserProperty();
    setSpeed(prop.getSpeed());
    for (int line = 0; line < rp.getRasterHeight(); line++) {
      Point lineStart = rasterStart.clone();
      lineStart.y += line;
      List<Byte> bytes = rp.getRasterLine(line);
      //remove heading zeroes
      while (bytes.size() > 0 && bytes.get(0) == 0) {
        bytes.remove(0);
        lineStart.x += 1;
      }
      //remove trailing zeroes
      while (bytes.size() > 0 && bytes.get(bytes.size() - 1) == 0) {
        bytes.remove(bytes.size() - 1);
      }
      if (bytes.size() > 0) {
        if (dirRight) {
          //move to the first nonempyt point of the line
          move(out, lineStart.x, lineStart.y, resolution);
          byte old = bytes.get(0);
          for (int pix = 0; pix < bytes.size(); pix++) {
            if (bytes.get(pix) != old) {
              if (old == 0) {
                move(out, lineStart.x + pix, lineStart.y, resolution);
              } else {
                setPressure(prop.getPower() * (0xFF & old) / 255);
                line(out, lineStart.x + pix - 1, lineStart.y, resolution);
                move(out, lineStart.x + pix, lineStart.y, resolution);
              }
              old = bytes.get(pix);
            }
          }
          //last point is also not "white"
          setPressure(prop.getPower() * (0xFF & bytes.get(bytes.size() - 1)) / 255);
          line(out, lineStart.x + bytes.size() - 1, lineStart.y, resolution);
        } else {
          //move to the last nonempty point of the line
          move(out, lineStart.x + bytes.size() - 1, lineStart.y, resolution);
          byte old = bytes.get(bytes.size() - 1);
          for (int pix = bytes.size() - 1; pix >= 0; pix--) {
            if (bytes.get(pix) != old || pix == 0) {
              if (old == 0) {
                move(out, lineStart.x + pix, lineStart.y, resolution);
              } else {
                setPressure(prop.getPower() * (0xFF & old) / 255);
                line(out, lineStart.x + pix + 1, lineStart.y, resolution);
                move(out, lineStart.x + pix, lineStart.y, resolution);
              }
              old = bytes.get(pix);
            }
          }
          //last point is also not "white"
          setPressure(prop.getPower() * (0xFF & bytes.get(0)) / 255);
          line(out, lineStart.x, lineStart.y, resolution);
        }
      }
      dirRight = !dirRight;
    }
    return result.toByteArray();
  }

  private int waitAvailable(int count, int timeout_tenths) throws IOException {
    // FIXME:
    // * RandomAccessFile() does not have an available() method. Sigh.
    // * Independent FileOutputStream and FileInputStream cannot be created for the 
    //   same printer device. The second call with fail with "device in use."
    // * Adding an extra FileInputStream using the same FileChannel from the RandomAccessFile
    //   never reports anything.
    // * A selector with SelectedKeys() can inform us on OP_READ. But this cannot be used on a FileChannel.
    // 
    // In POSIX we have ioctl(FIONREAD), select() or we could fork a reader thread.
    // For now we always pretend available input, and let read() block. Sigh.
    //
    // FileChannel ch = devr.getChannel();
    // System.err.printf("ch.size() = %d\n", ch.size());		// always reports 0
    //
    // FileDescriptor fd = devr.getFD();
    // FileInputStream fin = new FileInputStream(devr.getFD());		// never has anything available()
    for (int i = 0; i < timeout_tenths; i++) {
      int avail = 2;		// fin.available();
      if (avail >= count) {
        return avail;
      }
      try {
        Thread.sleep(100);	// Milliseconds
      } catch (InterruptedException e) {
      }
    }
    throw new IOException("Timeout");
  }

  private String readResponse(int char_timeout_tenths, int term) throws IOException {
    String r = "";
    while (true) {
      waitAvailable(1, char_timeout_tenths);
      int ch = devr.read();
      if (ch == -1) {
        throw new IOException("End of Stream");
      }
      if (ch == term) {
        return r;
      }
      r = r + new String(new byte[]{(byte)ch});
    }
  }

  private boolean WaitForReady() throws IOException {
    for (int i = 0; i < 100; i++)
      {
        devr.write(new byte[]{0x1b, 0x05});	// status request
        waitAvailable(2, 40);
        int status = devr.read();
        int dummy = devr.read();
        System.err.printf("device status %c: (0 ready, 1 moving, 2 empty tray)\n", status);
	if (status == '0') return true;
	else if (status == '1') System.out.println("moving");
	else if (status == '2') System.out.println("empty tray");
	else 
	  {
	    System.err.printf("Unknown status=%d\n", status);
	    return false;
	  }

	try {
          Thread.sleep(1000);	// Milliseconds
	} catch (InterruptedException e) {
	}
      }
    return false;
  }

  private byte[] generatePseudoRasterGCode(RasterPart rp, double resolution) throws UnsupportedEncodingException {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(result, true, "US-ASCII");
    boolean dirRight = true;
    Point rasterStart = rp.getRasterStart();
    PowerSpeedFocusProperty prop = (PowerSpeedFocusProperty) rp.getLaserProperty();
    setSpeed(prop.getSpeed());	// 1..10 speed
    setPressure(prop.getPower());	// 1..33 pressure
    setKnive((int)prop.getFocus());	// 0=pen, 18=knife
    for (int line = 0; line < rp.getRasterHeight(); line++) {
      Point lineStart = rasterStart.clone();
      lineStart.y += line;
      List<Byte> bytes = new LinkedList<Byte>();
      boolean lookForStart = true;
      for (int x = 0; x < rp.getRasterWidth(); x++) {
        if (lookForStart) {
          if (rp.isBlack(x, line)) {
            lookForStart = false;
            bytes.add((byte) 255);
          } else {
            lineStart.x += 1;
          }
        } else {
          bytes.add(rp.isBlack(x, line) ? (byte) 255 : (byte) 0);
        }
      }
      //remove trailing zeroes
      while (bytes.size() > 0 && bytes.get(bytes.size() - 1) == 0) {
        bytes.remove(bytes.size() - 1);
      }
      if (bytes.size() > 0) {
        if (dirRight) {
          //add some space to the left
          move(out, Math.max(0, (int) (lineStart.x - Util.mm2px(this.addSpacePerRasterLine, resolution))), lineStart.y, resolution);
          //move to the first nonempyt point of the line
          move(out, lineStart.x, lineStart.y, resolution);
          byte old = bytes.get(0);
          for (int pix = 0; pix < bytes.size(); pix++) {
            if (bytes.get(pix) != old) {
              if (old == 0) {
                move(out, lineStart.x + pix, lineStart.y, resolution);
              } else {
                setPressure(prop.getPower() * (0xFF & old) / 255);
                line(out, lineStart.x + pix - 1, lineStart.y, resolution);
                move(out, lineStart.x + pix, lineStart.y, resolution);
              }
              old = bytes.get(pix);
            }
          }
          //last point is also not "white"
          setPressure(prop.getPower() * (0xFF & bytes.get(bytes.size() - 1)) / 255);
          line(out, lineStart.x + bytes.size() - 1, lineStart.y, resolution);
          //add some space to the right
          move(out, Math.min((int) Util.mm2px(bedWidth, resolution), (int) (lineStart.x + bytes.size() - 1 + Util.mm2px(this.addSpacePerRasterLine, resolution))), lineStart.y, resolution);
        } else {
          //add some space to the right
          move(out, Math.min((int) Util.mm2px(bedWidth, resolution), (int) (lineStart.x + bytes.size() - 1 + Util.mm2px(this.addSpacePerRasterLine, resolution))), lineStart.y, resolution);
          //move to the last nonempty point of the line
          move(out, lineStart.x + bytes.size() - 1, lineStart.y, resolution);
          byte old = bytes.get(bytes.size() - 1);
          for (int pix = bytes.size() - 1; pix >= 0; pix--) {
            if (bytes.get(pix) != old || pix == 0) {
              if (old == 0) {
                move(out, lineStart.x + pix, lineStart.y, resolution);
              } else {
                setPressure(prop.getPower() * (0xFF & old) / 255);
                line(out, lineStart.x + pix + 1, lineStart.y, resolution);
                move(out, lineStart.x + pix, lineStart.y, resolution);
              }
              old = bytes.get(pix);
            }
          }
          //last point is also not "white"
          setPressure(prop.getPower() * (0xFF & bytes.get(0)) / 255);
          line(out, lineStart.x, lineStart.y, resolution);
          //add some space to the left
          move(out, Math.max(0, (int) (lineStart.x - Util.mm2px(this.addSpacePerRasterLine, resolution))), lineStart.y, resolution);
        }
      }
      dirRight = !dirRight;
    }
    return result.toByteArray();
  }

  @Override
  public void sendJob(LaserJob job, ProgressListener pl, List<String> warnings) throws IllegalJobException, IOException, Exception {
    pl.progressChanged(this, 0);
    this.currentPressure = -1;
    this.currentSpeed = -1;
    this.currentKnive = -1;
    pl.taskChanged(this, "checking job");
    checkJob(job);
    job.applyStartPoint();
    pl.taskChanged(this, "connecting");
    System.err.println("connecting");
    String devPortName = this.getDevPort();
    if (this.getDevPort().startsWith("file://"))
    {
        devPortName = devPortName.substring(7);
    }
    if (this.getDevPort().startsWith("usb://"))
    {
 	devPortName = devPortName.substring(6);
        // schema usb://VVVV:PPPP 
	System.err.println("usb://XXXX:YYY not implemented, try /dev/usb/lp0 instead.");
	throw new Exception("Port '"+this.getDevPort()+"' schema usb://XXXX:YYYY not implemented, try /dev/usb/lp0 instead.");
    }
    else
    {
	devr = new RandomAccessFile(devPortName, "rwd");
    }
    pl.taskChanged(this, "initializing device");
    System.err.println("initializing device");

    try {
      devr.write(new byte[]{0x1b, 0x04});	// initialize cutter
    } catch (IOException ioe) {
      ioe.printStackTrace();
    }

    WaitForReady();
    // devr.write(new byte[]{'T', 'T', 0x03});	// home the cutter
    devr.write("TT\003".getBytes());		// home the cutter

    // devr.write(new byte[]{'F', 'G', 0x03});	// query version
    devr.write("FG\003".getBytes());		// query version
    String cameo_version = readResponse(20, 0x03);	// "CAMEO V1.10    \x03"
    System.out.printf("device version: %s\n", cameo_version);

    if (currentSpeed > 0)
      {
        devr.write(String.format("!%d\003", currentSpeed).getBytes());
	System.out.printf("speed: %d\n", currentSpeed);
      }

    if (currentPressure > 0)
      {
        devr.write(String.format("FX%d\003", currentPressure).getBytes());
	System.out.printf("pressure: %d\n", currentPressure);
      }

    if (currentKnive > 0)
      {
        devr.write(String.format("FC%d\003", currentKnive).getBytes());
	System.out.printf("blade: %d\n", currentKnive);
      }

    if (true)
      {
        int trackenhancing = 0;
        devr.write(String.format("FY%d\003", trackenhancing).getBytes());
      }

    if (true)
      {
        int landscape = 0;
        devr.write(String.format("FN%d\003", landscape).getBytes());
      }
        
    // no idea what this does.
    // devr.write(new byte[]{'F','E','0',0x03});
    devr.write("FE0\003".getBytes());	

    // Again, no idea. Maybe something to do with registration marks?
    // devr.write(new byte[]{'T','B','7','1',0x03});
    devr.write("TB71\003".getBytes());
    String resp = readResponse(20, 0x03);
    if (resp != "    0,    0\003")
      {
        System.err.printf("setup: Invalid response from plotter: '%s'\n", resp);
	return;
      }

    if (true) return;

    pl.taskChanged(this, "sending");
    pl.progressChanged(this, 20);
    int i = 0;
    int max = job.getParts().size();
    for (JobPart p : job.getParts())
    {
      if (p instanceof Raster3dPart)
      {
        devr.write(this.generatePseudoRaster3dGCode((Raster3dPart) p, p.getDPI()));
      }
      else if (p instanceof RasterPart)
      {
        devr.write(this.generatePseudoRasterGCode((RasterPart) p, p.getDPI()));
      }
      else if (p instanceof VectorPart)
      {
        devr.write(this.generateVectorGCode((VectorPart) p, p.getDPI()));
      }
      i++;
      pl.progressChanged(this, 20 + (int) (i*(double) 60/max));
    }
    
    devr.write("&1,1,1,TB50,0\003".getBytes());
    devr.write("FO0\003".getBytes());			// feed the page out
    devr.write("H,".getBytes());			// halt?
    // devr.write(new byte[]{'&','1',',','1',',','1',',','T','B','5','0',',','0',0x03});		// ??
    // devr.write(new byte[]{'F','O','0',0x03});		// feed the page out
    // devr.write(new byte[]{'H',','});			// halt?
    devr.close();
    pl.taskChanged(this, "sent.");
    pl.progressChanged(this, 100);
  }
  private List<Double> resolutions;

  @Override
  public List<Double> getResolutions() {
    if (resolutions == null) {
      resolutions = Arrays.asList(new Double[]{
                500d
              });
    }
    return resolutions;
  }

  protected double bedWidth = 12 * 25.4;	// mm
  /**
   * Get the value of bedWidth
   *
   * @return the value of bedWidth
   */
  @Override
  public double getBedWidth() {
    return bedWidth;
  }
  /**
   * Set the value of bedWidth [mm]
   *
   * @param bedWidth new value of bedWidth
   */
  public void setBedWidth(double bedWidth) {
    this.bedWidth = bedWidth;
  }

  // unused dummy code. But needed to survive overloading errors.
  protected double bedHeight = 18 * 25.4.;	// 0.01 mm units
  /**
   * Get the value of bedHeight [mm]
   *
   * @return the value of bedHeight
   */
  @Override
  public double getBedHeight() {
    return bedHeight;	// dummy value, used for GUI!
  }
  /**
   * Set the value of bedHeight [mm]
   *
   * @param bedHeight new value of bedHeight
   */
  public void setBedHeight(double bedHeight) {
    this.bedHeight = bedHeight;
  }

  protected double hwDPI = 254.;	// 0.01 mm units
  /**
   * Get the value of hwDPI
   *
   * @return the value of hwDPI
   */
  public double getHwDPI() {
    return hwDPI;
  }
  /**
   * Set the value of hwDPI
   *
   * @param hwDPI new value of hwDPI
   */
  public void setHwDPI(double hwDPI) {
    this.hwDPI = hwDPI;
  }

  private static String[] settingAttributes = new String[]{
    SETTING_BEDWIDTH,
    SETTING_BEDHEIGHT,
    SETTING_HARDWARE_DPI,
    SETTING_DEVPORT,
    SETTING_RASTER_WHITESPACE,
  };

  @Override
  public String[] getPropertyKeys() {
    return settingAttributes;
  }

  @Override
  public Object getProperty(String attribute) {
    if (SETTING_RASTER_WHITESPACE.equals(attribute)) {
      return this.getAddSpacePerRasterLine();
    } else if (SETTING_DEVPORT.equals(attribute)) {
      return this.getDevPort();
    } else if (SETTING_BEDWIDTH.equals(attribute)) {
      return this.getBedWidth();
    } else if (SETTING_BEDHEIGHT.equals(attribute)) {
      return this.getBedHeight();
    } else if (SETTING_HARDWARE_DPI.equals(attribute)) {
      return this.getHwDPI();
    }
    return null;
  }

  @Override
  public void setProperty(String attribute, Object value) {
    if (SETTING_RASTER_WHITESPACE.equals(attribute)) {
      this.setAddSpacePerRasterLine((Double) value);
    } else if (SETTING_DEVPORT.equals(attribute)) {
      this.setDevPort((String) value);
    } else if (SETTING_BEDWIDTH.equals(attribute)) {
      this.setBedWidth((Double) value);
    } else if (SETTING_BEDHEIGHT.equals(attribute)) {
      this.setBedHeight((Double) value);
    } else if (SETTING_HARDWARE_DPI.equals(attribute)) {
      this.setHwDPI((Double) value);
    }
  }

  @Override
  public LaserCutter clone() {
    SilhouetteCameo clone = new SilhouetteCameo();
    clone.devPort = devPort;
    clone.bedWidth = bedWidth;
    clone.bedHeight = bedHeight;
    clone.hwDPI = hwDPI;
    clone.addSpacePerRasterLine = addSpacePerRasterLine;
    return clone;
  }
}
