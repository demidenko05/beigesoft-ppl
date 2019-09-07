/*
BSD 2-Clause License

Copyright (c) 2019, Beigesoft™
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.beigesoft.ppl;

import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.sql.ResultSet;

import org.beigesoft.fct.IFctAsm;
import org.beigesoft.fct.IFctPrcEnt;
import org.beigesoft.fct.IFctPrcFl;
import org.beigesoft.fct.IFctPrc;
import org.beigesoft.fct.FctBlc;
import org.beigesoft.fct.FctDbCp;
import org.beigesoft.fct.FctFlRep;
import org.beigesoft.fct.IFctCnToSt;
import org.beigesoft.fct.IFcFlFdSt;
import org.beigesoft.hld.IAttrs;
import org.beigesoft.hld.IHlNmClSt;
import org.beigesoft.rdb.Orm;
import org.beigesoft.web.FctMail;
import org.beigesoft.jdbc.FctPostgr;
import org.beigesoft.acc.fct.FctAcc;
import org.beigesoft.acc.fct.FcEnPrAc;
import org.beigesoft.acc.fct.FcPrNtAd;
import org.beigesoft.acc.fct.FcPrNtAc;
import org.beigesoft.acc.fct.FcPrFlAc;
import org.beigesoft.acc.fct.FcCnToStAi;
import org.beigesoft.acc.fct.FcFlFdAi;
import org.beigesoft.acc.hld.HlAcEnPr;
import org.beigesoft.ws.fct.FctWs;
import org.beigesoft.ws.fct.FcEnPrTr;
import org.beigesoft.ws.hld.HlTrEnPr;

/**
 * <p>Final configuration factory for Postgres JDBC.</p>
 *
 * @author Yury Demidenko
 */
public class FctAppPstg implements IFctAsm<ResultSet> {

  /**
   * <p>Main only factory.</p>
   **/
  private FctBlc<ResultSet> fctBlc;

  /**
   * <p>Get bean in lazy mode (if bean is null then initialize it).</p>
   * @param pRqVs request scoped vars
   * @param pBnNm - bean name
   * @return Object - requested bean or exception if not found
   * @throws Exception - an exception
   */
  @Override
  public final Object laz(final Map<String, Object> pRqVs,
    final String pBnNm) throws Exception {
    return this.fctBlc.laz(pRqVs, pBnNm);
  }

  /**
   * <p>Releases memory.</p>
   * @param pRqVs request scoped vars
   * @throws Exception - an exception
   */
  @Override
  public final void release(final Map<String, Object> pRqVs) throws Exception {
    this.fctBlc.release(pRqVs);
  }

  /**
   * <p>Puts beans by external AUX factory.</p>
   * @param pRqVs request scoped vars
   * @param pBnNm - bean name
   * @param pBean - bean
   * @throws Exception - an exception, e.g. if bean exists
   **/
  @Override
  public final void put(final Map<String, Object> pRqVs,
    final String pBnNm, final Object pBean) throws Exception {
    this.fctBlc.put(pRqVs, pBnNm, pBean);
  }

  /**
   * <p>Gets main factory for setting configuration parameters.</p>
   * @return Object - requested bean
   */
  @Override
  public final FctBlc<ResultSet> getFctBlc() {
    return this.fctBlc;
  }

  /**
   * <p>Initializes factory.</p>
   * @param pRvs request scoped vars
   * @param pCtxAttrs context attributes
   * @throws Exception - an exception, e.g. if bean exists
   */
  @Override
  public final void init(final Map<String, Object> pRvs,
    final IAttrs pCtxAttrs) throws Exception {
    this.fctBlc = new FctBlc<ResultSet>();
    this.fctBlc.getFctsAux().add(new FctPostgr());
    this.fctBlc.getFctsAux().add(new FctDbCp<ResultSet>());
    this.fctBlc.getFctsAux().add(new FctMail<ResultSet>());
    this.fctBlc.getFctsAux().add(new FctAcc<ResultSet>());
    FctWs<ResultSet> fctWs = new FctWs<ResultSet>();
    HashSet<IFctPrc> fpaws = new HashSet<IFctPrc>();
    FcPrPpl fpppl = new FcPrPpl();
    fpppl.setFctBlc(this.fctBlc);
    fpaws.add(fpppl);
    fctWs.setFctsPrc(fpaws);
    this.fctBlc.getFctsAux().add(fctWs);
    this.fctBlc.getFctsAux().add(new FctFlRep<ResultSet>());
    Set<IFctPrcEnt> fcsenpr = new HashSet<IFctPrcEnt>();
    FcEnPrAc<ResultSet> fcep = new FcEnPrAc<ResultSet>();
    fcep.setFctBlc(this.fctBlc);
    fcsenpr.add(fcep);
    FcEnPrTr<ResultSet> fcepws = new FcEnPrTr<ResultSet>();
    fcepws.setFctBlc(this.fctBlc);
    fcsenpr.add(fcepws);
    this.fctBlc.getFctDt().setFctsPrcEnt(fcsenpr);
    Set<IFctPrcFl> fcspf = new HashSet<IFctPrcFl>();
    FcPrFlAc<ResultSet> fcpf = new FcPrFlAc<ResultSet>();
    fcpf.setFctBlc(this.fctBlc);
    fcspf.add(fcpf);
    this.fctBlc.getFctDt().setFctrsPrcFl(fcspf);
    Set<IHlNmClSt> hldsBsEnPr = new LinkedHashSet<IHlNmClSt>();
    hldsBsEnPr.add(new HlAcEnPr());
    this.fctBlc.getFctDt().setHldsBsEnPr(hldsBsEnPr);
    HashSet<IFctPrc> fpas = new HashSet<IFctPrc>();
    FcPrNtAc<ResultSet> fctPrcNtrAc = new FcPrNtAc<ResultSet>();
    fctPrcNtrAc.setFctApp(this);
    fpas.add(fctPrcNtrAc);
    this.fctBlc.getFctDt().setFctsPrc(fpas);
    HashSet<IFctPrc> fpads = new HashSet<IFctPrc>();
    FcPrNtAd<ResultSet> fctPrcNtrAd = new FcPrNtAd<ResultSet>();
    fctPrcNtrAd.setFctBlc(this.fctBlc);
    fpads.add(fctPrcNtrAd);
    this.fctBlc.getFctDt().setFctsPrcAd(fpads);
    Set<IFctCnToSt> fcsCnToSt = new HashSet<IFctCnToSt>();
    FcCnToStAi<ResultSet> fcnst = new FcCnToStAi<ResultSet>();
    fcnst.setFctBlc(this.fctBlc);
    fcsCnToSt.add(fcnst);
    this.fctBlc.getFctDt().setFcsCnToSt(fcsCnToSt);
    Set<IFcFlFdSt> fcsFlFdSt = new HashSet<IFcFlFdSt>();
    FcFlFdAi<ResultSet> ffdst = new FcFlFdAi<ResultSet>();
    ffdst.setFctBlc(this.fctBlc);
    fcsFlFdSt.add(ffdst);
    this.fctBlc.getFctDt().setFcsFlFdSt(fcsFlFdSt);
    this.fctBlc.getFctDt().setIsPstg(true);
    Set<IHlNmClSt> hldsAdEnPr = new LinkedHashSet<IHlNmClSt>();
    hldsAdEnPr.add(new HlTrEnPr());
    this.fctBlc.getFctDt().setHldsAdEnPr(hldsAdEnPr);
    //creating/upgrading DB on start:
    Orm<ResultSet> orm = this.fctBlc.lazOrm(pRvs);
    orm.init(pRvs);
      //free memory:
    orm.getSetng().release();
  }
}
