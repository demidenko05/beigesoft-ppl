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
import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Date;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;

import com.paypal.api.payments.Amount;
import com.paypal.api.payments.Details;
import com.paypal.api.payments.Item;
import com.paypal.api.payments.ItemList;
import com.paypal.api.payments.Links;
import com.paypal.api.payments.Payer;
import com.paypal.api.payments.Payment;
import com.paypal.api.payments.PaymentExecution;
import com.paypal.api.payments.RedirectUrls;
import com.paypal.api.payments.Transaction;
import com.paypal.base.rest.APIContext;

import org.beigesoft.exc.ExcCode;
import org.beigesoft.mdl.IReqDt;
import org.beigesoft.mdl.IIdLn;
import org.beigesoft.mdl.ColVals;
import org.beigesoft.log.ILog;
import org.beigesoft.hnd.IHndSpam;
import org.beigesoft.prc.IPrc;
import org.beigesoft.rdb.IOrm;
import org.beigesoft.rdb.IRdb;
import org.beigesoft.rdb.SrvClVl;
import org.beigesoft.srv.INumStr;
import org.beigesoft.acc.mdlp.AcStg;
import org.beigesoft.ws.mdl.EOrdStat;
import org.beigesoft.ws.mdl.EPaymMth;
import org.beigesoft.ws.mdl.Purch;
import org.beigesoft.ws.mdlb.AOrdLn;
import org.beigesoft.ws.mdlb.ATxLn;
import org.beigesoft.ws.mdlp.Cart;
import org.beigesoft.ws.mdlp.CuOrSe;
import org.beigesoft.ws.mdlp.CuOrSeGdLn;
import org.beigesoft.ws.mdlp.CuOrSeSrLn;
import org.beigesoft.ws.mdlp.CuOrSeTxLn;
import org.beigesoft.ws.mdlp.CuOr;
import org.beigesoft.ws.mdlp.CuOrTxLn;
import org.beigesoft.ws.mdlp.CuOrSrLn;
import org.beigesoft.ws.mdlp.CuOrGdLn;
import org.beigesoft.ws.mdlp.PayMd;
import org.beigesoft.ws.mdlp.SePayMd;
import org.beigesoft.ws.mdlp.Buyer;
import org.beigesoft.ws.mdlp.SeSel;
import org.beigesoft.ws.mdlp.AddStg;
import org.beigesoft.ws.mdlp.OnlPay;
import org.beigesoft.ws.srv.ISrCart;
import org.beigesoft.ws.srv.IAcpOrd;
import org.beigesoft.ws.srv.ICncOrd;
import org.beigesoft.ws.srv.IBuySr;

/**
 * <p>Service that makes orders payed through PayPal.
 * It creates (manages) transactions itself.
 * It makes circle:
 * <ul>
 * <li>phase 1 - accept (book) all buyer's new orders, if OK, then
 * creates PayPal payment, then adds payment as "pplPayId" to request attributes
 * to response JSON payment id.
 * </li>
 * <li>phase 2 - buyer accept payment and sent buyerId, so
 * this service executes payment</li>
 * </ul>
 * If buyer did not pay, then accepted order's service will cancel them
 * during its invocation, i.e. before accepting new orders it should checks
 * for such non-payed ones and cancels them. Checking is simple -
 * find out all BOOKED orders with ONLINE/PAYPAL method and date is
 * less then 30 minutes from current time. Client application should also send
 * canceling request in this case. This is processor either for webstore
 * owner's orders or a S.E.Seller's. That is it must not be several online
 * payee in same purchase, e.g. order with WS owner's items and
 * PayPal method, and order with S.E.Seller1 items and PayPal method.
 * It must be only record in PAYMD/SEPAYMD table with NME=PAYPAL that holds
 * MDE="mode", SEC1="clientID" and SEC2="clientSecret".
 * </p>
 *
 * @author Yury Demidenko
 */
public class PrPpl implements IPrc {

  /**
   * <p>Logger.</p>
   **/
  private ILog log;

  /**
   * <p>Logger security.</p>
   **/
  private ILog secLog;

  /**
   * <p>Database service.</p>
   **/
  private IRdb<ResultSet> rdb;

  /**
   * <p>ORM service.</p>
   **/
  private IOrm orm;

  /**
   * <p>Column values service.</p>
   **/
  private SrvClVl srvClVl;

  /**
   * <p>Shopping Cart service.</p>
   **/
  private ISrCart srCart;

  /**
   * <p>Accept buyer's new orders service.</p>
   **/
  private IAcpOrd acpOrd;

  /**
   * <p>Cancel accepted buyer's orders service.</p>
   **/
  private ICncOrd cncOrd;

  /**
   * <p>Service print number.</p>
   **/
  private INumStr numStr;

  /**
   * <p>Buyer service.</p>
   **/
  private IBuySr buySr;

  /**
   * <p>Spam handler.</p>
   **/
  private IHndSpam hndSpam;

  /**
   * <p>Process request.</p>
   * @param pRvs request scoped vars
   * @param pRqDt Request Data
   * @throws Exception - an exception
   **/
  @Override
  public final void process(final Map<String, Object> pRvs,
    final IReqDt pRqDt) throws Exception {
    if (!pRqDt.getReqUrl().toString().toLowerCase().startsWith("https")) {
      throw new Exception("PPL http not supported!!!");
    }
    boolean dbgSh = getLog().getDbgSh(getClass(), 17000);
    AddStg tastg = (AddStg) pRvs.get("tastg");
    String payerID = pRqDt.getParam("payerID");
    try {
      this.rdb.setAcmt(false);
      this.rdb.setTrIsl(tastg.getBkTr());
      this.rdb.begin();
      if (payerID != null) {
        //execution payment:
        phase2(pRvs, pRqDt, tastg, payerID);
      } else {
        String pur = pRqDt.getParam("pur");
        if (pur != null) { //cancel/return:
          if (dbgSh) {
            getLog().debug(pRvs, PrPpl.class, "Cancel/return...");
          }
          Long buyrId = Long.parseLong(pRqDt.getParam("buyr"));
          Buyer buyr = new Buyer();
          buyr.setIid(buyrId);
          OnlPay onpa = new OnlPay();
          onpa.setIid(buyr);
          Map<String, Object> vs = new HashMap<String, Object>();
          this.orm.refrEnt(pRvs, vs, onpa);
          if (onpa.getIid() == null) {
            this.hndSpam.handle(pRvs, pRqDt, 100,
              "OnlPay not found for buyer ID: " + buyrId);
            throw new ExcCode(ExcCode.SPAM, "OnlPay not found for buyer ID: "
              + buyrId);
          }
          long now = new Date().getTime();
          if (now - onpa.getDat().getTime() > 600000) { //10 minutes
            this.hndSpam.handle(pRvs, pRqDt, 100,
              "OnlPay outdated for buyer ID: " + buyrId);
            throw new ExcCode(ExcCode.SPAM, "OnlPay outdated for buyer ID: "
              + buyrId);
          }
          this.cncOrd.cancel(pRvs, buyr, onpa.getPur(), EOrdStat.BOOKED,
            EOrdStat.NEW);
          pRvs.put("pplPayId", onpa.getPayId());
          String cnc = pRqDt.getParam("cnc");
          if (cnc != null) {
            pRvs.put("pplStat", "canceled");
          } else {
            pRvs.put("pplStat", "return");
          }
          getLog().info(pRvs, PrPpl.class, "buyer/pid/result " + buyr
            .getIid() + "/" + onpa.getPayId() + "/" + pRvs.get("pplStat"));
        } else {
          //phase 1, creating payment:
          phase1(pRvs, pRqDt, tastg);
        }
      }
      this.rdb.commit();
    } catch (Exception ex) {
      if (!this.rdb.getAcmt()) {
        this.rdb.rollBack();
      }
      throw ex;
    } finally {
      this.rdb.release();
    }
    //forced renderer:
    pRqDt.setAttr("rnd", "ppl");
  }

  /**
   * <p>It makes phase 2 - execution payment.</p>
   * @param pRvs request scoped vars
   * @param pRqDt Request Data
   * @param pSetAdd AddStg
   * @param pPayerId Payer ID
   * @throws Exception - an exception
   **/
  public final void phase2(final Map<String, Object> pRvs,
    final IReqDt pRqDt, final AddStg pSetAdd,
      final String pPayerId) throws Exception {
    boolean dbgSh = getLog().getDbgSh(getClass(), 17002);
    if (dbgSh) {
      getLog().debug(pRvs, PrPpl.class, "Phase2...");
    }
    //phase 2, executing payment:
    Buyer buyer = this.buySr.getAuthBuyr(pRvs, pRqDt);
    if (buyer == null) {
      this.hndSpam.handle(pRvs, pRqDt, 1000, "PrPpl. buyer auth err!");
      throw new ExcCode(ExcCode.SPAM, "PrPpl. buyer auth err!");
    }
    String paymentID = pRqDt.getParam("paymentID");
    if (paymentID == null) {
      this.hndSpam.handle(pRvs, pRqDt, 1000,
        "There is no paymentID for payerID: " + pPayerId);
      throw new ExcCode(ExcCode.SPAM,
        "There is no paymentID for payerID: " + pPayerId);
    }
    Map<String, Object> vs = new HashMap<String, Object>();
    OnlPay onpa = new OnlPay();
    onpa.setIid(buyer);
    this.orm.refrEnt(pRvs, vs, onpa);
    if (onpa.getIid() == null) {
      this.hndSpam.handle(pRvs, pRqDt, 100,
        "OnlPay not found for buyer ID: " + buyer.getIid());
      throw new ExcCode(ExcCode.SPAM, "OnlPay not found for buyer ID: "
        + buyer.getIid());
    }
    if (!onpa.getPayId().equals(paymentID)) {
      this.hndSpam.handle(pRvs, pRqDt, 100,
        "OnlPay payId doesn't not match for buyer ID: " + buyer.getIid());
      throw new ExcCode(ExcCode.SPAM,
        "OnlPay payId doesn't not match for buyer ID: " + buyer.getIid());
    }
    long now = new Date().getTime();
    if (now - onpa.getDat().getTime() > 600000) { //10 minutes
      this.hndSpam.handle(pRvs, pRqDt, 100,
        "OnlPay outdated for buyer ID: " + buyer.getIid());
      throw new ExcCode(ExcCode.SPAM, "OnlPay outdated for buyer ID: "
        + buyer.getIid());
    }
    PayMd payMd = null;
    if (pSetAdd.getOnlMd() == 1 || onpa.getSelr() == null) {
      //Owner is only online payee:
      List<PayMd> payMds = this.orm.retLstCnd(pRvs, vs, PayMd.class,
        "where NME='PAYPAL'");
      if (payMds.size() == 1) {
        payMd = payMds.get(0);
      }
    } else {
      List<SePayMd> payMds = this.orm.retLstCnd(pRvs, vs, SePayMd.class,
        "where NME='PAYPAL' and SEL=" + onpa.getSelr().getIid().getIid());
      if (payMds.size() == 1) {
        payMd = payMds.get(0);
      }
    }
    if (payMd == null) {
      this.cncOrd.cancel(pRvs, buyer, onpa.getPur(), EOrdStat.BOOKED,
        EOrdStat.NEW);
      this.log.error(pRvs, getClass(), "There is no payment method!!!");
    } else {
      APIContext apiCon = new APIContext(payMd.getSec1(),
        payMd.getSec2(), payMd.getMde());
      Payment pay = new Payment();
      pay.setId(paymentID);
      PaymentExecution payExec = new PaymentExecution();
      payExec.setPayerId(pPayerId);
      pay.execute(apiCon, payExec);
      pRvs.put("pplPayId", pay.getId());
      pRvs.put("pplStat", "executed");
      ColVals cvs = new ColVals();
      this.srvClVl.put(cvs, "ver", new Date().getTime());
      this.srvClVl.put(cvs, "stas", EOrdStat.PAYED.ordinal());
      this.rdb.update(CuOr.class, cvs, "PAYM in(9,10) and BUYR="
        + onpa.getBuyr().getIid() + " and PUR=" + onpa.getPur());
      this.rdb.update(CuOrSe.class, cvs, "PAYM in(9,10) and BUYR="
        + onpa.getBuyr().getIid() + " and PUR=" + onpa.getPur());
      this.srCart.emptyCart(pRvs, buyer);
    }
  }

  /**
   * <p>It makes phase 1 - create payment.</p>
   * @param pRvs request scoped vars
   * @param pRqDt Request Data
   * @param pSetAdd AddStg
   * @throws Exception - an exception
   **/
  public final void phase1(final Map<String, Object> pRvs,
    final IReqDt pRqDt, final AddStg pSetAdd) throws Exception {
    boolean dbgSh = getLog().getDbgSh(getClass(), 17001);
    if (dbgSh) {
      getLog().debug(pRvs, PrPpl.class, "Phase1...");
    }
    //it must be request from authorized buyer's browser:
    Cart cart = this.srCart.getCart(pRvs, pRqDt, false, true);
    if (cart != null && !cart.getErr()) {
      //phase 1, creating payment:
      Map<String, Object> vs = new HashMap<String, Object>();
      List<PayMd> payMds = null;
      List<SePayMd> payMdsSe = null;
      String wherePpl = "where NME='PAYPAL'";
      payMds = this.orm.retLstCnd(pRvs, vs, PayMd.class, wherePpl);
      payMdsSe = this.orm.retLstCnd(pRvs, vs, SePayMd.class, wherePpl);
      Purch pur = this.acpOrd.accept(pRvs, pRqDt, cart.getBuyr());
      CuOr ord = null;
      SeSel sel = null;
      List<CuOr> ppords = null;
      List<CuOrSe> ppsords = null;
      if (pur.getOrds() != null && pur.getOrds().size() > 0) {
        //checking orders with PayPal payment:
        for (CuOr or : pur.getOrds()) {
          if (or.getPaym().equals(EPaymMth.PAYPAL)
            || or.getPaym().equals(EPaymMth.PAYPAL_ANY)) {
            if (ppords == null) {
              ppords = new ArrayList<CuOr>();
            }
            ppords.add(or);
          }
        }
      }
      if (pur.getSords() != null && pur.getSords().size() > 0) {
        //checking S.E. orders with PayPal payment:
        for (CuOrSe or : pur.getSords()) {
          if (or.getPaym().equals(EPaymMth.PAYPAL)
            || or.getPaym().equals(EPaymMth.PAYPAL_ANY)) {
            if (ppsords == null) {
              ppsords = new ArrayList<CuOrSe>();
              sel = or.getSelr();
            } else if (pSetAdd.getOnlMd() == 0 && !sel.getIid().getIid()
              .equals(or.getSelr().getIid().getIid())) {
              throw new Exception("Several S.E.Payee in purchase!");
            }
            ppsords.add(or);
          }
        }
      }
      PayMd payMd = null;
      if (pSetAdd.getOnlMd() == 1 || ppords != null && ppsords == null) {
        //payee - owner
        if (payMds.size() != 1) {
          throw new Exception("There is no properly PPL PayMd");
        } else {
          payMd = payMds.get(0);
        }
      } else { //there is only payee - S.E.seller:
        SePayMd payMdSe = null;
        for (SePayMd pm : payMdsSe) {
          if (payMdSe == null && sel.getIid().getIid()
            .equals(pm.getSelr().getIid().getIid())) {
            payMdSe = pm;
            payMd = pm;
          } else if (payMdSe != null && payMdSe.getSelr().getIid()
            .getIid().equals(pm.getSelr().getIid().getIid())) {
            throw new Exception(
              "There is no properly PPL SePayMd for seller#"
                + sel.getIid().getIid());
          }
        }
      }
      if (ppords != null && ppords.size() > 0) {
        //proceed PayPal orders:
        ord = makePplOrds(pRvs, pRqDt, ppords, cart, CuOrGdLn.class,
          CuOrSrLn.class, CuOrTxLn.class);
        ord.setCurr(ppords.get(0).getCurr());
        ord.setPur(ppords.get(0).getPur());
      }
      if (ppsords != null && ppsords.size() > 0) {
        //proceed PayPal S.E. orders:
        if (ord == null) {
          ord = makePplOrds(pRvs, pRqDt, ppsords, cart, CuOrSeGdLn.class,
            CuOrSeSrLn.class, CuOrSeTxLn.class);
          ord.setCurr(ppsords.get(0).getCurr());
          ord.setPur(ppsords.get(0).getPur());
        } else {
          CuOr sord = makePplOrds(pRvs, pRqDt, ppsords, cart,
            CuOrSeGdLn.class, CuOrSeSrLn.class, CuOrSeTxLn.class);
          if (sord.getGoods() != null) {
            if (ord.getGoods() != null) {
              ord.getGoods().addAll(sord.getGoods());
            } else {
              ord.setGoods(sord.getGoods());
            }
          }
          if (sord.getServs() != null) {
            if (ord.getServs() != null) {
              ord.getServs().addAll(sord.getServs());
            } else {
              ord.setServs(sord.getServs());
            }
          }
          if (sord.getTaxes() != null) {
            if (ord.getTaxes() != null) {
              ord.getTaxes().addAll(sord.getTaxes());
            } else {
              ord.setTaxes(sord.getTaxes());
            }
          }
          ord.setTot(ord.getTot().add(sord.getTot()));
          ord.setToTx(ord.getToTx().add(sord.getToTx()));
          ord.setSubt(ord.getSubt().add(sord.getSubt()));
        }
      }
      if (ord != null) {
        createPay(pRvs, pRqDt, ord, payMd, sel);
      } else {
        throw new Exception("Can't create PPL payment!");
      }
    } else if (cart != null && cart.getErr()) {
      throw new Exception("Cart with error for buyer ID: " + cart.getBuyr()
        .getIid());
    } else {
      this.hndSpam.handle(pRvs, pRqDt, 1000, "PrPpl. buyer auth err!");
      throw new ExcCode(ExcCode.SPAM, "PrPpl. buyer auth err!");
    }
  }

  /**
   * <p>Makes consolidate order with  webstore owner's items.</p>
   * @param <GL> good line type
   * @param <SL> service line type
   * @param <TL> tax line type
   * @param pRvs request scoped vars
   * @param pRqDt request data
   * @param pPplOrds orders
   * @param pCart cart
   * @param pGlCl good line class
   * @param pSlCl service line class
   * @param pTlCl tax line class
   * @return consolidated order or null if not possible
   * @throws Exception - an exception
   **/
  public final <GL extends AOrdLn, SL extends AOrdLn, TL extends ATxLn>
   CuOr makePplOrds(final Map<String, Object> pRvs,
    final IReqDt pRqDt, final List<? extends IIdLn> pPplOrds,
     final Cart pCart, final Class<GL> pGlCl, final Class<SL> pSlCl,
      final Class<TL> pTlCl) throws Exception {
    CuOr ord = null;
    StringBuffer ordIds = new StringBuffer();
    for (int i = 0; i < pPplOrds.size();  i++) {
      if (i == 0) {
        ordIds.append(pPplOrds.get(i).getIid().toString());
      } else {
        ordIds.append("," + pPplOrds.get(i).getIid());
      }
    }
    Map<String, Object> vs = new HashMap<String, Object>();
    List<CuOrGdLn> goods = null;
    List<CuOrSrLn> servs = null;
    List<CuOrTxLn> taxs = null;
    //checking invoice basis tax:
    String[] ndFl = new String[] {"nme", "pri", "quan", "subt", "tot", "toTx"};
    Arrays.sort(ndFl);
    vs.put(pGlCl.getSimpleName() + "ndFds", ndFl);
    List<GL> gls = this.orm.retLstCnd(pRvs, vs, pGlCl,
      "where OWNR in (" + ordIds.toString() + ")"); vs.clear();
    if (gls.size() > 0) {
      if (pGlCl == CuOrGdLn.class) {
        goods = (List<CuOrGdLn>) gls;
      } else {
        goods = new ArrayList<CuOrGdLn>();
        for (GL il : gls) {
          CuOrGdLn itm = new CuOrGdLn();
          itm.setIid(il.getIid());
          itm.setNme(il.getNme());
          itm.setPri(il.getPri());
          itm.setQuan(il.getQuan());
          itm.setSubt(il.getSubt());
          itm.setTot(il.getTot());
          itm.setToTx(il.getToTx());
          goods.add(itm);
        }
      }
    }
    vs.put(pSlCl.getSimpleName() + "ndFds", ndFl);
    List<SL> sls = this.orm.retLstCnd(pRvs, vs, pSlCl,
      "where OWNR in (" + ordIds.toString() + ")"); vs.clear();
    if (sls.size() > 0) {
      if (pSlCl == CuOrSrLn.class) {
        servs = (List<CuOrSrLn>) sls;
      } else {
        servs = new ArrayList<CuOrSrLn>();
        for (SL il : sls) {
          CuOrSrLn itm = new CuOrSrLn();
          itm.setIid(il.getIid());
          itm.setNme(il.getNme());
          itm.setPri(il.getPri());
          itm.setQuan(il.getQuan());
          itm.setSubt(il.getSubt());
          itm.setTot(il.getTot());
          itm.setToTx(il.getToTx());
          servs.add(itm);
        }
      }
    }
    if (goods != null || servs != null) {
      ord = new CuOr();
      ord.setBuyr(pCart.getBuyr());
      ord.setGoods(goods);
      ord.setServs(servs);
      if (goods != null) {
        for (CuOrGdLn il : goods) {
          //price inclusive tax???
          //https://stackoverflow.com/questions/24285424/
          //can-the-paypal-rest-api-display-order-items-with-tax-included
          if (il.getToTx().compareTo(BigDecimal.ZERO) == 1
        && il.getPri().multiply(il.getQuan()).compareTo(il.getTot()) == 0) {
      il.setPri(il.getSubt().divide(il.getQuan(), 2, RoundingMode.HALF_UP));
          }
          ord.setTot(ord.getTot().add(il.getTot()));
          ord.setToTx(ord.getToTx().add(il.getToTx()));
          ord.setSubt(ord.getSubt().add(il.getSubt()));
        }
      }
      if (servs != null) {
        for (CuOrSrLn il : servs) {
          if (il.getToTx().compareTo(BigDecimal.ZERO) == 1
        && il.getPri().multiply(il.getQuan()).compareTo(il.getTot()) == 0) {
      il.setPri(il.getSubt().divide(il.getQuan(), 2, RoundingMode.HALF_UP));
          }
          ord.setTot(ord.getTot().add(il.getTot()));
          ord.setToTx(ord.getToTx().add(il.getToTx()));
          ord.setSubt(ord.getSubt().add(il.getSubt()));
        }
      }
      if (ord.getToTx().compareTo(BigDecimal.ZERO) == 0) {
        //invoice basis:
        vs.put(pTlCl.getSimpleName() + "ndFds", new String[] {"toTx"});
        List<TL> tls = this.orm.retLstCnd(pRvs, vs, pTlCl,
          "where OWNR in (" + ordIds.toString() + ")"); vs.clear();
        if (tls.size() > 0) {
          if (pTlCl == CuOrTxLn.class) {
            taxs = (List<CuOrTxLn>) tls;
          } else {
            taxs = new ArrayList<CuOrTxLn>();
            for (TL tl : tls) {
              CuOrTxLn tln = new CuOrTxLn();
              tln.setIid(tl.getIid());
              tln.setTot(tl.getTot());
              taxs.add(tln);
              ord.setToTx(ord.getToTx().add(tl.getTot()));
            }
          }
          ord.setTaxes(taxs);
        }
      }
    }
    return ord;
  }

  /**
   * <p>Creates PayPal payment out of given orders lines with
   * webstore owner's items.</p>
   * @param pRvs request scoped vars
   * @param pRqDt request data
   * @param pOrd consolidated order
   * @param pPayMd client codes
   * @param pSel S.E. Seller or NULL
   * @throws Exception - an exception
   **/
  public final void createPay(final Map<String, Object> pRvs,
    final IReqDt pRqDt, final CuOr pOrd,
      final PayMd pPayMd, final SeSel pSel) throws Exception {
    boolean dbgSh = getLog().getDbgSh(getClass(), 17003);
    AcStg as = (AcStg) pRvs.get("astg");
    APIContext apiCon = new APIContext(pPayMd.getSec1(), pPayMd.getSec2(),
      pPayMd.getMde());
    Details details = new Details();
    //TODO special headed service "shipping"
    //details.setShipping("1");
    details.setSubtotal(prn(pOrd.getSubt(), as.getPrDp()));
    if (pOrd.getToTx().compareTo(BigDecimal.ZERO) == 1) {
      details.setTax(prn(pOrd.getToTx(), as.getPrDp()));
    }
    if (dbgSh) {
      getLog().debug(pRvs, PrPpl.class,
        "Order tot/subt/tax/curr: " + pOrd.getTot() + "/" + pOrd.getSubt() + "/"
          + pOrd.getToTx() + "/" + pOrd.getCurr().getStCo());
    }
    Amount amount = new Amount();
    amount.setCurrency(pOrd.getCurr().getStCo());
    amount.setTotal(prn(pOrd.getTot(), as.getPrDp()));
    amount.setDetails(details);
    Transaction transaction = new Transaction();
    transaction.setAmount(amount);
    //transaction.setDescription();
    List<Item> items = new ArrayList<Item>();
    if (pOrd.getGoods() != null) {
      for (CuOrGdLn il : pOrd.getGoods()) {
        if (dbgSh) {
          getLog().debug(pRvs, PrPpl.class,
            "item/price/quant/tax: " + il.getNme() + "/" + il.getPri()
              + "/" + il.getQuan() + "/" + il.getToTx());
        }
        Item item = new Item();
        item.setName(il.getNme());
        item.setQuantity(Long.valueOf(il.getQuan().longValue()).toString());
        item.setCurrency(pOrd.getCurr().getStCo());
        item.setPrice(prn(il.getPri(), as.getPrDp()));
        if (il.getToTx().compareTo(BigDecimal.ZERO) == 1) {
          item.setTax(prn(il.getToTx(), as.getPrDp()));
        }
        if (dbgSh) {
          getLog().debug(pRvs, PrPpl.class, "Added item nme/quan/pri/tax/curr: "
           + item.getName() + "/" + item.getQuantity() + "/" + item.getPrice()
              + "/" + item.getTax() + "/" + item.getCurrency());
        }
        items.add(item);
      }
    }
    if (pOrd.getServs() != null) {
      for (CuOrSrLn il : pOrd.getServs()) {
        if (dbgSh) {
          getLog().debug(pRvs, PrPpl.class,
            "item/price/quant/tax: " + il.getNme() + "/" + il.getPri()
              + "/" + il.getQuan() + "/" + il.getToTx());
        }
        Item item = new Item();
        item.setName(il.getNme());
        item.setQuantity(Long.valueOf(il.getQuan().longValue()).toString());
        item.setCurrency(pOrd.getCurr().getStCo());
        item.setPrice(prn(il.getPri(), as.getPrDp()));
        if (il.getToTx().compareTo(BigDecimal.ZERO) == 1) {
          item.setTax(prn(il.getToTx(), as.getPrDp()));
        }
        if (dbgSh) {
          getLog().debug(pRvs, PrPpl.class, "Added item nme/quan/pri/tax/curr: "
           + item.getName() + "/" + item.getQuantity() + "/" + item.getPrice()
              + "/" + item.getTax() + "/" + item.getCurrency());
        }
        items.add(item);
      }
    }
    ItemList itemList = new ItemList();
    itemList.setItems(items);
    transaction.setItemList(itemList);
    List<Transaction> transactions = new ArrayList<Transaction>();
    transactions.add(transaction);
    Payer payer = new Payer();
    payer.setPaymentMethod("paypal");
    Payment payment = new Payment();
    payment.setIntent("sale");
    payment.setPayer(payer);
    payment.setTransactions(transactions);
    RedirectUrls redUrls = new RedirectUrls();
    String pars = "&pur=" + pOrd.getPur() + "&buyr=" + pOrd.getBuyr().getIid();
    if (pSel != null) {
      pars += "&sel" + pSel.getIid().getIid();
    }
    String u = pRqDt.getReqUrl().toString() + "?prcRed=" + pRqDt
      .getParam("prcRed");
    redUrls.setCancelUrl(u + "&prc=PrPpl&cnc=1" + pars);
    redUrls.setReturnUrl(u + "&prc=PrPpl" + pars);
    payment.setRedirectUrls(redUrls);
    Payment crPay = payment.create(apiCon);
    Iterator<Links> links = crPay.getLinks().iterator();
    while (links.hasNext()) {
      Links link = links.next();
      if (link.getRel().equalsIgnoreCase("approval_url")) {
        pRqDt.setAttr("redirectURL", link.getHref());
      }
    }
    Map<String, Object> vs = new HashMap<String, Object>();
    OnlPay onpa = new OnlPay();
    onpa.setIid(pOrd.getBuyr());
    this.orm.refrEnt(pRvs, vs, onpa);
    if (onpa.getIid() == null) {
      onpa.setIid(pOrd.getBuyr());
      onpa.setIsNew(true);
    }
    onpa.setPur(pOrd.getPur());
    onpa.setSelr(pSel);
    onpa.setPayId(crPay.getId());
    onpa.setDat(new Date());
    if (onpa.getIsNew()) {
      this.orm.insIdNln(pRvs, vs, onpa);
    } else {
      this.orm.update(pRvs, vs, onpa);
    }
    pRvs.put("pplPayId", crPay.getId());
    pRvs.put("pplStat", "created");
    if (dbgSh) {
      getLog().debug(pRvs, PrPpl.class,
        "Cancel URL: " + redUrls.getCancelUrl());
    }
  }

  /**
   * <p>Simple delegator to print number.</p>
   * @param pVal value
   * @param pDp decimal places
   * @return String
   **/
  public final String prn(final BigDecimal pVal, final Integer pDp) {
    return this.numStr.frmt(pVal.toString(), ".", "", pDp, 3);
  }

  //Simple getters and setters:
  /**
   * <p>Getter for log.</p>
   * @return ILog
   **/
  public final ILog getLog() {
    return this.log;
  }

  /**
   * <p>Setter for log.</p>
   * @param pLog reference
   **/
  public final void setLog(final ILog pLog) {
    this.log = pLog;
  }

  /**
   * <p>Getter for secLog.</p>
   * @return ILog
   **/
  public final ILog getSecLog() {
    return this.secLog;
  }

  /**
   * <p>Setter for secLog.</p>
   * @param pSecLog reference
   **/
  public final void setSecLog(final ILog pSecLog) {
    this.secLog = pSecLog;
  }

  /**
   * <p>Getter for rdb.</p>
   * @return IRdb<ResultSet>
   **/
  public final IRdb<ResultSet> getRdb() {
    return this.rdb;
  }

  /**
   * <p>Setter for rdb.</p>
   * @param pRdb reference
   **/
  public final void setRdb(final IRdb<ResultSet> pRdb) {
    this.rdb = pRdb;
  }

  /**
   * <p>Getter for orm.</p>
   * @return IRdb<ResultSet>
   **/
  public final IOrm getOrm() {
    return this.orm;
  }

  /**
   * <p>Setter for orm.</p>
   * @param pOrm reference
   **/
  public final void setOrm(final IOrm pOrm) {
    this.orm = pOrm;
  }

  /**
   * <p>Getter for srCart.</p>
   * @return ISrCart
   **/
  public final ISrCart getSrCart() {
    return this.srCart;
  }

  /**
   * <p>Setter for srCart.</p>
   * @param pSrCart reference
   **/
  public final void setSrCart(final ISrCart pSrCart) {
    this.srCart = pSrCart;
  }

  /**
   * <p>Getter for acpOrd.</p>
   * @return IAcpOrd
   **/
  public final IAcpOrd getAcpOrd() {
    return this.acpOrd;
  }

  /**
   * <p>Setter for acpOrd.</p>
   * @param pAcpOrd reference
   **/
  public final void setAcpOrd(final IAcpOrd pAcpOrd) {
    this.acpOrd = pAcpOrd;
  }

  /**
   * <p>Getter for cncOrd.</p>
   * @return ICncOrd
   **/
  public final ICncOrd getCncOrd() {
    return this.cncOrd;
  }

  /**
   * <p>Setter for cncOrd.</p>
   * @param pCncOrd reference
   **/
  public final void setCncOrd(final ICncOrd pCncOrd) {
    this.cncOrd = pCncOrd;
  }

  /**
   * <p>Getter for numStr.</p>
   * @return INumStr
   **/
  public final INumStr getNumStr() {
    return this.numStr;
  }

  /**
   * <p>Setter for numStr.</p>
   * @param pNumStr reference
   **/
  public final void setNumStr(final INumStr pNumStr) {
    this.numStr = pNumStr;
  }

  /**
   * <p>Getter for buySr.</p>
   * @return IBuySr
   **/
  public final IBuySr getBuySr() {
    return this.buySr;
  }

  /**
   * <p>Setter for buySr.</p>
   * @param pBuySr reference
   **/
  public final void setBuySr(final IBuySr pBuySr) {
    this.buySr = pBuySr;
  }

  /**
   * <p>Getter for hndSpam.</p>
   * @return IHndSpam
   **/
  public final IHndSpam getHndSpam() {
    return this.hndSpam;
  }

  /**
   * <p>Setter for hndSpam.</p>
   * @param pHndSpam reference
   **/
  public final void setHndSpam(final IHndSpam pHndSpam) {
    this.hndSpam = pHndSpam;
  }

  /**
   * <p>Getter for srvClVl.</p>
   * @return SrvClVl
   **/
  public final SrvClVl getSrvClVl() {
    return this.srvClVl;
  }

  /**
   * <p>Setter for srvClVl.</p>
   * @param pSrvClVl reference
   **/
  public final void setSrvClVl(final SrvClVl pSrvClVl) {
    this.srvClVl = pSrvClVl;
  }
}
