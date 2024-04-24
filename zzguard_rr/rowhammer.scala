package freechips.rocketchip.zzguardrr

import chisel3._
import chisel3.util._




class rowhammer extends Module{
  val io = IO(new Bundle{
    val din_valid      = Input(Bool())
    val din_opcode     = Input(UInt(7.W))
    val din_addr       = Input(UInt(40.W))
    val flashen  = Output(Bool())
    val dout_addr_up      = Output(UInt(40.W))
    val dout_addr_down      = Output(UInt(40.W))
    val dout_addr_cache      = Output(UInt(40.W))
  })
 
 val count = RegInit(0.U(10.W))
 val ldstvalid = io.din_opcode === "b0100011".U || io.din_opcode === "b0000011".U
when(io.din_valid){
  when(ldstvalid && (count<998.U)){
    count := count + 1.U
    io.flashen := false.B
  }.elsewhen(ldstvalid && (count===998.U))
  {io.flashen := true.B
   count := 0.U
  }.otherwise{
    io.flashen := false.B
  }
}.otherwise{
    io.flashen := false.B
  }
  
  val addr = RegInit(0.U(40.W))
  when(io.flashen){
    addr := io.din_addr
  }
  io.dout_addr_up := addr + 4096.U
  io.dout_addr_down := addr + 0.U
  io.dout_addr_cache := addr + 2048.U

}