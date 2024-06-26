// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

package freechips.rocketchip.tile

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.rocket._
import freechips.rocketchip.subsystem.HierarchicalElementCrossingParamsLike
import freechips.rocketchip.util._
import freechips.rocketchip.prci.{ClockSinkParameters}

//===== zzguardrr: Start ====//
import freechips.rocketchip.zzguardrr._
//===== zzguardrr: End   ====//

//===== zzguardrr: Start ====//
class ClockDividerN(div: Int) extends BlackBox(Map("DIV" -> div)) with HasBlackBoxResource {
  require(div > 0);
  val io = IO(new Bundle {
    val clk_out = Output(Clock())
    val clk_in  = Input(Clock())
  })
  addResource("/vsrc/ClockDividerN.sv")
}
//===== zzguardrr: End   ====//



case class RocketTileBoundaryBufferParams(force: Boolean = false)

case class RocketTileParams(
    core: RocketCoreParams = RocketCoreParams(),
    icache: Option[ICacheParams] = Some(ICacheParams()),
    dcache: Option[DCacheParams] = Some(DCacheParams()),
    btb: Option[BTBParams] = Some(BTBParams()),
    dataScratchpadBytes: Int = 0,
    tileId: Int = 0,
    beuAddr: Option[BigInt] = None,
    blockerCtrlAddr: Option[BigInt] = None,
    clockSinkParams: ClockSinkParameters = ClockSinkParameters(),
    boundaryBuffers: Option[RocketTileBoundaryBufferParams] = None
    ) extends InstantiableTileParams[RocketTile] {
  require(icache.isDefined)
  require(dcache.isDefined)
  val baseName = "rockettile"
  val uniqueName = s"${baseName}_$tileId"
  def instantiate(crossing: HierarchicalElementCrossingParamsLike, lookup: LookupByHartIdImpl)(implicit p: Parameters): RocketTile = {
    new RocketTile(this, crossing, lookup)
  }
}

class RocketTile private(
      val rocketParams: RocketTileParams,
      crossing: ClockCrossingType,
      lookup: LookupByHartIdImpl,
      q: Parameters)
    extends BaseTile(rocketParams, crossing, lookup, q)
    with SinksExternalInterrupts
    with SourcesExternalNotifications
    with HasLazyRoCC  // implies CanHaveSharedFPU with CanHavePTW with HasHellaCache
    with HasHellaCache
    with HasICacheFrontend
{
  // Private constructor ensures altered LazyModule.p is used implicitly
  def this(params: RocketTileParams, crossing: HierarchicalElementCrossingParamsLike, lookup: LookupByHartIdImpl)(implicit p: Parameters) =
    this(params, crossing.crossingType, lookup, p)

  val intOutwardNode = rocketParams.beuAddr map { _ => IntIdentityNode() }
  val slaveNode = TLIdentityNode()
  val masterNode = visibilityNode

  val dtim_adapter = tileParams.dcache.flatMap { d => d.scratch.map { s =>
    LazyModule(new ScratchpadSlavePort(AddressSet.misaligned(s, d.dataScratchpadBytes), lazyCoreParamsView.coreDataBytes, tileParams.core.useAtomics && !tileParams.core.useAtomicsOnlyForIO))
  }}
  dtim_adapter.foreach(lm => connectTLSlave(lm.node, lm.node.portParams.head.beatBytes))

  val bus_error_unit = rocketParams.beuAddr map { a =>
    val beu = LazyModule(new BusErrorUnit(new L1BusErrors, BusErrorUnitParams(a)))
    intOutwardNode.get := beu.intNode
    connectTLSlave(beu.node, xBytes)
    beu
  }

  //===== zzguardrr: Start ====//
  val zzzzz_3 = Module(new Zzzzz_Imp)
  //===== zzguardrr: End   ====//

  val tile_master_blocker =
    tileParams.blockerCtrlAddr
      .map(BasicBusBlockerParams(_, xBytes, masterPortBeatBytes, deadlock = true))
      .map(bp => LazyModule(new BasicBusBlocker(bp)))

  tile_master_blocker.foreach(lm => connectTLSlave(lm.controlNode, xBytes))

  // TODO: this doesn't block other masters, e.g. RoCCs
  tlOtherMastersNode := tile_master_blocker.map { _.node := tlMasterXbar.node } getOrElse { tlMasterXbar.node }
  masterNode :=* tlOtherMastersNode
  DisableMonitors { implicit p => tlSlaveXbar.node :*= slaveNode }

  nDCachePorts += 1 /*core */ + (dtim_adapter.isDefined).toInt

  val dtimProperty = dtim_adapter.map(d => Map(
    "sifive,dtim" -> d.device.asProperty)).getOrElse(Nil)

  val itimProperty = frontend.icache.itimProperty.toSeq.flatMap(p => Map("sifive,itim" -> p))

  val beuProperty = bus_error_unit.map(d => Map(
          "sifive,buserror" -> d.device.asProperty)).getOrElse(Nil)

  val cpuDevice: SimpleDevice = new SimpleDevice("cpu", Seq("sifive,rocket0", "riscv")) {
    override def parent = Some(ResourceAnchors.cpus)
    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      Description(name, mapping ++ cpuProperties ++ nextLevelCacheProperty
                  ++ tileProperties ++ dtimProperty ++ itimProperty ++ beuProperty)
    }
  }

  ResourceBinding {
    Resource(cpuDevice, "reg").bind(ResourceAddress(tileId))
  }

  override lazy val module = new RocketTileModuleImp(this)

  override def makeMasterBoundaryBuffers(crossing: ClockCrossingType)(implicit p: Parameters) = (rocketParams.boundaryBuffers, crossing) match {
    case (Some(RocketTileBoundaryBufferParams(true )), _)                   => TLBuffer()
    case (Some(RocketTileBoundaryBufferParams(false)), _: RationalCrossing) => TLBuffer(BufferParams.none, BufferParams.flow, BufferParams.none, BufferParams.flow, BufferParams(1))
    case _ => TLBuffer(BufferParams.none)
  }

  override def makeSlaveBoundaryBuffers(crossing: ClockCrossingType)(implicit p: Parameters) = (rocketParams.boundaryBuffers, crossing) match {
    case (Some(RocketTileBoundaryBufferParams(true )), _)                   => TLBuffer()
    case (Some(RocketTileBoundaryBufferParams(false)), _: RationalCrossing) => TLBuffer(BufferParams.flow, BufferParams.none, BufferParams.none, BufferParams.none, BufferParams.none)
    case _ => TLBuffer(BufferParams.none)
  }
}

class RocketTileModuleImp(outer: RocketTile) extends BaseTileModuleImp(outer)
    with HasFpuOpt
    with HasLazyRoCCModule
    with HasICacheFrontendModule {
  Annotated.params(this, outer.rocketParams)

  val core = Module(new Rocket(outer)(outer.p))

  // //===== zzguardrr: Start ====//
  // val zzzzz_4 = Module(new Zzzzz_Imp)
  // val clk_div = Module(new ClockDividerN(4))
  // clk_div.io.clk_in := core.clock
  // zzzzz_4.clock := clk_div.io.clk_out
  
  // val opcode = WireDefault(0.U(7.W))
  // opcode := core.io.ins(6,0)

  // val fifo = Module(new asyncfifo(16, 32))
  // fifo.io.clk_r := clk_div.io.clk_out
  // fifo.io.wdata := core.io.ins
  // fifo.io.ren := true.B
  // when((opcode === "b0000011".U || opcode === "b0100011".U || opcode === "b1100111".U || opcode === "b1101111".U) && core.io.valid){
  //   fifo.io.wen := true.B
  // }
  // .otherwise{
  //   fifo.io.wen := false.B
  // }

      
  // //===== zzguardrr: End   ====//

  // reset vector is connected in the Frontend to s2_pc
  core.io.reset_vector := DontCare

  // Report unrecoverable error conditions; for now the only cause is cache ECC errors
  outer.reportHalt(List(outer.dcache.module.io.errors))

  // Report when the tile has ceased to retire instructions; for now the only cause is clock gating
  outer.reportCease(outer.rocketParams.core.clockGate.option(
    !outer.dcache.module.io.cpu.clock_enabled &&
    !outer.frontend.module.io.cpu.clock_enabled &&
    !ptw.io.dpath.clock_enabled &&
    core.io.cease))

  outer.reportWFI(Some(core.io.wfi))

  outer.decodeCoreInterrupts(core.io.interrupts) // Decode the interrupt vector

  outer.bus_error_unit.foreach { beu =>
    core.io.interrupts.buserror.get := beu.module.io.interrupt
    beu.module.io.errors.dcache := outer.dcache.module.io.errors
    beu.module.io.errors.icache := outer.frontend.module.io.errors
  }

  core.io.interrupts.nmi.foreach { nmi => nmi := outer.nmiSinkNode.get.bundle }

  // Pass through various external constants and reports that were bundle-bridged into the tile
  outer.traceSourceNode.bundle <> core.io.trace
  core.io.traceStall := outer.traceAuxSinkNode.bundle.stall
  outer.bpwatchSourceNode.bundle <> core.io.bpwatch
  core.io.hartid := outer.hartIdSinkNode.bundle
  require(core.io.hartid.getWidth >= outer.hartIdSinkNode.bundle.getWidth,
    s"core hartid wire (${core.io.hartid.getWidth}b) truncates external hartid wire (${outer.hartIdSinkNode.bundle.getWidth}b)")

  // Connect the core pipeline to other intra-tile modules
  outer.frontend.module.io.cpu <> core.io.imem
  dcachePorts += core.io.dmem // TODO outer.dcachePorts += () => module.core.io.dmem ??
  fpuOpt foreach { fpu =>
    core.io.fpu :<>= fpu.io.waiveAs[FPUCoreIO](_.cp_req, _.cp_resp)
    fpu.io.cp_req := DontCare
    fpu.io.cp_resp := DontCare
  }
  if (fpuOpt.isEmpty) {
    core.io.fpu := DontCare
  }
  core.io.ptw <> ptw.io.dpath

  // Connect the coprocessor interfaces
  if (outer.roccs.size > 0) {
    cmdRouter.get.io.in <> core.io.rocc.cmd
    outer.roccs.foreach{ lm =>
      lm.module.io.exception := core.io.rocc.exception
      lm.module.io.fpu_req.ready := DontCare
      lm.module.io.fpu_resp.valid := DontCare
      lm.module.io.fpu_resp.bits.data := DontCare
      lm.module.io.fpu_resp.bits.exc := DontCare
    }
    core.io.rocc.resp <> respArb.get.io.out
    core.io.rocc.busy <> (cmdRouter.get.io.busy || outer.roccs.map(_.module.io.busy).reduce(_ || _))
    core.io.rocc.interrupt := outer.roccs.map(_.module.io.interrupt).reduce(_ || _)
    (core.io.rocc.csrs zip roccCSRIOs.flatten).foreach { t => t._2 <> t._1 }

    //===== zzguardrrlht: Start ====//
    cmdRouter.get.io.valid := core.io.rocc.valid
    cmdRouter.get.io.pc := core.io.rocc.pc
    cmdRouter.get.io.ins := core.io.rocc.ins
    cmdRouter.get.io.wdata := core.io.rocc.wdata
    cmdRouter.get.io.mdata := core.io.rocc.mdata
    cmdRouter.get.io.dmemaddr := core.io.rocc.dmemaddr
    core.io.yaofull_counter:= cmdRouter.get.io.yaofull_counter_out
    core.io.rocc.yaofull_counter_out:= cmdRouter.get.io.yaofull_counter_out
    core.io.rowham_addr_up := cmdRouter.get.io.rowham_addr_up_out
    core.io.rowham_addr_down := cmdRouter.get.io.rowham_addr_down_out
    core.io.rowham_addr_cache := cmdRouter.get.io.rowham_addr_cache_out
    core.io.rowham_flashen  := cmdRouter.get.io.rowham_flashen_out
    core.io.rocc.rowham_addr_up_out := cmdRouter.get.io.rowham_addr_up_out
    core.io.rocc.rowham_addr_down_out := cmdRouter.get.io.rowham_addr_down_out
    core.io.rocc.rowham_addr_cache_out := cmdRouter.get.io.rowham_addr_cache_out
    core.io.rocc.rowham_flashen_out  := cmdRouter.get.io.rowham_flashen_out
    


  //===== zzguardrrlht: end ====//
  } else {
    // tie off
    core.io.rocc.cmd.ready := false.B
    core.io.rocc.resp.valid := false.B
    core.io.rocc.resp.bits := DontCare
    core.io.rocc.busy := DontCare
    core.io.rocc.interrupt := DontCare
  }
  // Dont care mem since not all RoCC need accessing memory
  core.io.rocc.mem := DontCare

  // Rocket has higher priority to DTIM than other TileLink clients
  outer.dtim_adapter.foreach { lm => dcachePorts += lm.module.io.dmem }

  // TODO eliminate this redundancy
  val h = dcachePorts.size
  val c = core.dcacheArbPorts
  val o = outer.nDCachePorts
  require(h == c, s"port list size was $h, core expected $c")
  require(h == o, s"port list size was $h, outer counted $o")
  // TODO figure out how to move the below into their respective mix-ins
  dcacheArb.io.requestor <> dcachePorts.toSeq
  ptw.io.requestor <> ptwPorts.toSeq

//   //===== zzguardrr: Start ====//
//   val zzguard = Module(new zzguardrr)
//   zzguard.io.valid      := core.io.valid
//   zzguard.io.din_pc     := core.io.pc
//   zzguard.io.din_ins    := core.io.ins
//   zzguard.io.din_wdata  := core.io.wdata
//   zzguard.io.din_mdata  := core.io.mdata
//   core.io.yaofull_counter  := zzguard.io.yaofull_counter

//   val zzguard_ram = Module(new zzguardrr_ram)
//   zzguard_ram.io.valid      := core.io.valid
//   zzguard_ram.io.din_pc     := core.io.pc
//   zzguard_ram.io.din_ins    := core.io.ins
//   zzguard_ram.io.din_wdata  := core.io.wdata
//   zzguard_ram.io.din_mdata  := core.io.mdata
//   //core.io.yaofull_counter  := zzguard_ram.io.yaofull_counter

//   when(core.io.ins(6,0) === "b0001011".U){
//     zzguard_ram.io.addr         := core.io.rs1
//     zzguard_ram.io.data_in      := core.io.rs2
//     zzguard_ram.io.funct        := core.io.ins(31,25)
//     zzguard_ram.io.valid_config := true.B
//   }
//   .otherwise{
//     zzguard_ram.io.addr         := 0.U
//     zzguard_ram.io.data_in      := 0.U
//     zzguard_ram.io.funct        := 0.U
//     zzguard_ram.io.valid_config := false.B
//   }

//   //===== zzguardrr: End   ====//

// }
    }
trait HasFpuOpt { this: RocketTileModuleImp =>
  val fpuOpt = outer.tileParams.core.fpu.map(params => Module(new FPU(params)(outer.p)))
}
