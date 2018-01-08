/**
 * This file is part of LibLaserCut.
 * Copyright (C) 2011 - 2014 Thomas Oster <mail@thomas-oster.de>
 * Copyright (c) 2018 Klaus Kämpf <kkaempf@suse.de>
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

import com.t_oster.liblasercut.PowerSpeedFocusFrequencyProperty;
import org.apache.commons.lang3.ArrayUtils;
import java.util.Arrays;

/**
 *
 * @author kkaempf
 */
public class ThunderLaserProperty extends PowerSpeedFocusFrequencyProperty {

  private int min_power = 10;

  public ThunderLaserProperty()
  {
  }

  /**
   * Get the value of min power
   *
   * @return int
   */
  public int getMinPower()
  {
    return min_power;
  }

  /**
   * Set the value of min power
   *
   * @param new min power
   */
  public void setMinPower(int power)
  {
    this.min_power = power;
  }

  private static String[] minPowerPropertyNames = new String[]{"min power"};
  @Override
  public String[] getPropertyKeys()
  {
    return (String [])ArrayUtils.addAll(minPowerPropertyNames, super.getPropertyKeys());
  }

  @Override
  public Object getProperty(String name)
  {
    if ("min power".equals(name))
    {
      return (Integer) this.getMinPower();
    }
    else
    {
      return super.getProperty(name);
    }
  }

  @Override
  public void setProperty(String name, Object value)
  {
    if ("min power".equals(name))
    {
      this.setMinPower((Integer) value);
    }
    else
    {
      super.setProperty(name, value);
    }
  }

  @Override
  public ThunderLaserProperty clone()
  {
    ThunderLaserProperty result = new ThunderLaserProperty();
    for (String s:this.getPropertyKeys())
    {
      result.setProperty(s, this.getProperty(s));
    }
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    final ThunderLaserProperty other = (ThunderLaserProperty) obj;
    if (this.min_power != other.min_power) {
      return false;
    }
    return super.equals(other);
  }

  @Override
  public int hashCode() {
    int hash = 5;
    hash = 97 * hash + min_power;
    hash = 97 * hash + super.hashCode();
    return hash;
  }

  public String toString()
  {
      return "ThunderLaserProperty(min power="+getMinPower()+", power="+getPower()+", speed="+getSpeed()+", focus="+getFocus()+", frequency="+getFrequency()+")";
  }

}
