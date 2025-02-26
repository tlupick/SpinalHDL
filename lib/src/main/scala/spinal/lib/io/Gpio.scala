package spinal.lib.io

import spinal.core._
import spinal.core.fiber.Fiber
import spinal.lib._
import spinal.lib.bus.amba3.apb.{Apb3, Apb3Config, Apb3SlaveFactory}
import spinal.lib.bus.bmb.{Bmb, BmbAccessCapabilities, BmbAccessParameter, BmbParameter, BmbSlaveFactory}
import spinal.lib.bus.misc.BusSlaveFactory
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink.fabric
import spinal.lib.misc.InterruptNode

object Gpio {
  case class Parameter(width : Int,                   //Number of pin
                       var input : Seq[Int] = null,   //List of pin id which can be inputs (null mean all)
                       var output : Seq[Int]  = null, //List of pin id which can be outputs (null mean all)
                       interrupt : Seq[Int]  = Nil,   //List of pin id which can be used as interrupt source
                       readBufferLength : Int = 2)    //Number of syncronisation stages

  abstract class Ctrl[T <: spinal.core.Data with IMasterSlave](p: Gpio.Parameter,
                                                                busType: HardType[T],
                                                                factory: T => BusSlaveFactory
                                                               ) extends Component {

    if(p.input == null) p.input = (0 until p.width)
    if(p.output == null) p.output = (0 until p.width)

    val io = new Bundle {
      val gpio = master(TriStateArray(p.width bits))
      val bus = slave(busType())
      val interrupt = out(Bits(p.width bits))
    }

    val mapper = factory(io.bus)
    val syncronized = Delay(io.gpio.read, p.readBufferLength)
    val last = RegNext(syncronized)


    for(i <- 0 until p.width){
      if(p.input.contains(i)) mapper.read(syncronized(i), 0x00, i)
      if(p.output.contains(i)) mapper.driveAndRead(io.gpio.write(i), 0x04, i) else io.gpio.write(i) := False
      if(p.output.contains(i) && p.input.contains(i)) mapper.driveAndRead(io.gpio.writeEnable(i), 0x08, i) init(False) else io.gpio.writeEnable(i) := Bool(p.output.contains(i))
    }

    val interrupt = new Area {
      val enable = new Area{
        val high, low, rise, fall = Bits(p.width bits)
      }

      val valid = ((enable.high & syncronized)
                | (enable.low & ~syncronized)
                | (enable.rise & (syncronized & ~last))
                | (enable.fall & (~syncronized & last)))

      for(i <- 0 until p.width){
        if(p.interrupt.contains(i)){
          io.interrupt(i) := valid(i)
          mapper.driveAndRead(enable.rise(i), 0x20,i) init(False)
          mapper.driveAndRead(enable.fall(i), 0x24,i) init(False)
          mapper.driveAndRead(enable.high(i), 0x28,i) init(False)
          mapper.driveAndRead(enable.low(i),  0x2C,i) init(False)
        } else {
          io.interrupt(i) := False
          enable.rise(i) := False
          enable.fall(i) := False
          enable.high(i) := False
          enable.low(i) := False
        }
      }
    }
  }

  def addressWidth = 8
}


case class Apb3Gpio2(  parameter: Gpio.Parameter,
                       busConfig: Apb3Config = Apb3Config(12, 32)
                     ) extends Gpio.Ctrl[Apb3] (
  parameter,
  Apb3(busConfig),
  Apb3SlaveFactory(_)
)
object BmbGpio2{
  def getBmbCapabilities(accessSource : BmbAccessCapabilities) = BmbSlaveFactory.getBmbCapabilities(
    accessSource,
    addressWidth = Gpio.addressWidth,
    dataWidth = 32
  )

  def getSupported(proposed: tilelink.M2sSupport) = tilelink.SlaveFactory.getSupported(
    addressWidth = Gpio.addressWidth,
    dataWidth = 32,
    allowBurst = false,
    proposed = proposed
  )
}
case class BmbGpio2(   parameter: Gpio.Parameter,
                       busConfig: BmbParameter
                    ) extends Gpio.Ctrl[Bmb] (
  parameter,
  Bmb(busConfig),
  BmbSlaveFactory(_)
)

case class TilelinkGpio2(parameter: Gpio.Parameter,
                       busConfig: tilelink.BusParameter
                   ) extends Gpio.Ctrl[tilelink.Bus] (
  parameter,
  tilelink.Bus(busConfig),
  new tilelink.SlaveFactory(_, false)
)

case class TilelinkGpio2Fiber(param : Gpio.Parameter) extends Area{
  val ctrl = fabric.Node.up()

  val intIdSet = param.interrupt.toSet
  val interrupt = for(pinId <- 0 until param.width) yield intIdSet.contains(pinId) generate InterruptNode.master()

  val logic = Fiber build new Area{
    ctrl.m2s.supported.load(BmbGpio2.getSupported(ctrl.m2s.proposed))
    ctrl.s2m.none()

    val core = TilelinkGpio2(param, ctrl.bus.p)
    core.io.bus <> ctrl.bus
    for(pinId <- param.interrupt) interrupt(pinId).flag := core.io.interrupt(pinId)
    val gpio = core.io.gpio.toIo
  }
}
