package com.t_oster.liblasercut.drivers.ruida;

import gnu.io.*;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public class Serial {
  
  private SerialPort serialPort;
  private InputStream in;          
  private OutputStream out;

  Serial()
  {
  }

  public SerialPort connect ( String portName ) throws Exception
  {
    try { in.close(); } catch(Exception e) {}
    try { out.close(); } catch(Exception e) {}

    CommPortIdentifier portIdentifier = CommPortIdentifier.getPortIdentifier(portName);
    if ( portIdentifier.isCurrentlyOwned() )
    {
      System.out.println("Error: Port is currently in use");
    }
    else
    {
      CommPort commPort = portIdentifier.open(this.getClass().getName(),2000);

      if ( commPort instanceof SerialPort )
      {
        System.out.println("Serial.open has a commPort");
        serialPort = (SerialPort) commPort;
        serialPort.setSerialPortParams(921600, SerialPort.DATABITS_8, SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);
        serialPort.setRTS(false);
        TimeUnit.MILLISECONDS.sleep(5);
        serialPort.setDTR(false);
        TimeUnit.MILLISECONDS.sleep(100);
        in = serialPort.getInputStream();
        out = serialPort.getOutputStream();
        return serialPort;
      }
      else
      {
        System.out.println("Error: Only serial ports are handled by this example.");
      }
    }
    return null;
  }

  public void write(byte[] data) throws IOException
  {
    out.write(data);
    return;
  }
  
  private static byte[] buf = new byte[1024];
  public byte[] read(int max) throws IOException, UnsupportedCommOperationException
  {
    int idx = 0;
    
    if (max > 1024) {
      System.out.println(String.format("Serial.read max %d > 1024", max));
      return null;
    }
    serialPort.enableReceiveTimeout(500); // start with 500msec for first byte
    byte first = (byte)in.read();
    if (first == 0) {
      System.out.println("Serial.read timeout");
      // timeout
      return buf;
    }
    buf[0] = first;
    serialPort.enableReceiveTimeout(1); // 1msec
//    System.out.println(String.format("%d: 0x%02x", idx, buf[idx]));
    while((this.in.available() != 0) && (idx < max)) {
      idx++;
      buf[idx] = (byte)in.read();
//      System.out.println(String.format("%d: 0x%02x", idx, buf[idx]));
    }
//    System.out.println(String.format("Serial.read got %d bytes", idx+1));
    return Arrays.copyOfRange(buf, 0, idx+1);
  }

  public OutputStream outputStream()
  {
    return out;
  }

  public void listPorts()
  {
    java.util.Enumeration<CommPortIdentifier> portEnum = CommPortIdentifier.getPortIdentifiers();
    while ( portEnum.hasMoreElements() ) 
    {
      CommPortIdentifier portIdentifier = portEnum.nextElement();
      System.out.println(portIdentifier.getName()  +  " - " +  getPortTypeName(portIdentifier.getPortType()) );
    }        
  }
    
  private String getPortTypeName ( int portType )
  {
    switch ( portType )
    {
    case CommPortIdentifier.PORT_I2C:
      return "I2C";
    case CommPortIdentifier.PORT_PARALLEL:
      return "Parallel";
    case CommPortIdentifier.PORT_RAW:
      return "Raw";
    case CommPortIdentifier.PORT_RS485:
      return "RS485";
    case CommPortIdentifier.PORT_SERIAL:
      return "Serial";
    default:
      return "unknown type";
    }
  }
}
