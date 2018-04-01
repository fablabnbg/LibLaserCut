/**
 * This file is part of LibLaserCut.
 * Copyright (C) 2018 Klaus Kämpf <kkaempf@fabfolk.com>
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

import java.net.InetAddress;
import java.net.DatagramPacket; 
import java.net.DatagramSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;


public class UdpStream extends OutputStream
{
  private Integer port = 80;
  private String hostname = "";
  private DatagramSocket socket;
  private InetAddress address;
  private ByteArrayOutputStream bos;
  public static final int NETWORK_TIMEOUT = 3000;
  public static final int SOURCE_PORT = 40200; // used by rdworks in Windows

  public UdpStream(String hostname, Integer port) throws IOException
  {
    this.hostname = hostname;
    this.port = port;
    System.out.println("UdpStream(" + hostname + ", " + port + ")");
    socket = new DatagramSocket(SOURCE_PORT);
    address = InetAddress.getByName(hostname);
    bos = new ByteArrayOutputStream();
  }

  public void write(int i) throws IOException
  {
    bos.write(i);
  }

  public void write(byte[] data) throws IOException
  {
    send(bos);
    bos.reset();
    System.out.println("UdpStream.write(data " + data.length + " bytes)");
    send(data)
  }

  private void send(byte[] ary) throws IOException
  {
    System.out.println("UdpStream.send(ary " + ary.size() + " bytes)");
    if (ary.size() > 0) {
      DatagramPacket packet = new DatagramPacket(ary.toByteArray(), ary.size(), address, port);
      socket.send(packet); 
    }
  }

  public void close() throws IOException
  {
    send(bos);
    System.out.println("UdpStream.close()");
    socket.close();
  }
}
