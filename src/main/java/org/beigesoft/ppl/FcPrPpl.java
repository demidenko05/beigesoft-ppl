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

import java.util.Map;
import java.util.HashMap;
import java.sql.ResultSet;

import org.beigesoft.fct.FctBlc;
import org.beigesoft.fct.IFctPrc;
import org.beigesoft.hnd.HndSpam;
import org.beigesoft.prc.IPrc;
import org.beigesoft.rdb.IRdb;
import org.beigesoft.ws.srv.IBuySr;
import org.beigesoft.ws.srv.ISrCart;
import org.beigesoft.ws.srv.IAcpOrd;
import org.beigesoft.ws.srv.ICncOrd;

/**
 * <p>Additional PPL factory of web-store public processors.</p>
 *
 * @author Yury Demidenko
 */
public class FcPrPpl implements IFctPrc {

  /**
   * <p>Main factory.</p>
   **/
  private FctBlc<ResultSet> fctBlc;

  //requested data:
  /**
   * <p>Processors map.</p>
   **/
  private final Map<String, IPrc> procs = new HashMap<String, IPrc>();

  /**
   * <p>Get processor in lazy mode (if bean is null then initialize it).</p>
   * @param pRvs request scoped vars
   * @param pPrNm - filler name
   * @return requested processor or null
   * @throws Exception - an exception
   */
  public final IPrc laz(final Map<String, Object> pRvs,
    final String pPrNm) throws Exception {
    IPrc rz = this.procs.get(pPrNm);
    if (rz == null && PrPpl.class.getSimpleName().equals(pPrNm)) {
      //inner factory, i.e. already synchronized
      rz = crPuPrPpl(pRvs);
    }
    return rz;
  }

  /**
   * <p>Create and put into the Map PrPpl.</p>
   * @param pRvs request scoped vars
   * @return PrPpl
   * @throws Exception - an exception
   */
  private PrPpl crPuPrPpl(final Map<String, Object> pRvs) throws Exception {
    PrPpl rz = new PrPpl();
    rz.setLog(this.fctBlc.lazLogStd(pRvs));
    rz.setSecLog(this.fctBlc.lazLogSec(pRvs));
    @SuppressWarnings("unchecked")
    IRdb<ResultSet> rdb = (IRdb<ResultSet>) this.fctBlc
      .laz(pRvs, IRdb.class.getSimpleName());
    rz.setRdb(rdb);
    ICncOrd cncOrd = (ICncOrd) this.fctBlc
      .laz(pRvs, ICncOrd.class.getSimpleName());
    rz.setCncOrd(cncOrd);
    IAcpOrd acpOrd = (IAcpOrd) this.fctBlc
      .laz(pRvs, IAcpOrd.class.getSimpleName());
    rz.setAcpOrd(acpOrd);
    HndSpam sph = (HndSpam) this.fctBlc
      .laz(pRvs, HndSpam.class.getSimpleName());
    rz.setHndSpam(sph);
    ISrCart srCart = (ISrCart) this.fctBlc
      .laz(pRvs, ISrCart.class.getSimpleName());
    rz.setSrCart(srCart);
    IBuySr buySr = (IBuySr) this.fctBlc.laz(pRvs, IBuySr.class.getSimpleName());
    rz.setBuySr(buySr);
    rz.setOrm(this.fctBlc.lazOrm(pRvs));
    rz.setNumStr(this.fctBlc.lazNumStr(pRvs));
    rz.setSrvClVl(this.fctBlc.lazSrvClVl(pRvs));
    this.procs.put(PrPpl.class.getSimpleName(), rz);
    this.fctBlc.lazLogStd(pRvs).info(pRvs, getClass(),
      PrPpl.class.getSimpleName() + " has been created.");
    return rz;
  }

  //Simple getters and setters:
  /**
   * <p>Getter for fctBlc.</p>
   * @return FctBlc<ResultSet>
   **/
  public final FctBlc<ResultSet> getFctBlc() {
    return this.fctBlc;
  }

  /**
   * <p>Setter for fctBlc.</p>
   * @param pFctBlc reference
   **/
  public final void setFctBlc(final FctBlc<ResultSet> pFctBlc) {
    this.fctBlc = pFctBlc;
  }
}
