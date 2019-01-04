package org.beigesoft.ppl;

/*
 * Copyright (c) 2018 Beigesoft™
 *
 * Licensed under the GNU General Public License (GPL), Version 2.0
 * (the "License");
 * you may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at
 *
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.en.html
 */

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Date;
import java.math.BigDecimal;

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

import org.beigesoft.model.IRequestData;
import org.beigesoft.model.IHasIdLongVersion;
import org.beigesoft.log.ILogger;
import org.beigesoft.service.IProcessor;
import org.beigesoft.service.ISrvOrm;
import org.beigesoft.service.ISrvDatabase;
import org.beigesoft.webstore.model.EOrdStat;
import org.beigesoft.webstore.model.EPaymentMethod;
import org.beigesoft.webstore.model.Purch;
import org.beigesoft.webstore.persistable.base.AOrdLn;
import org.beigesoft.webstore.persistable.base.ATaxLn;
import org.beigesoft.webstore.persistable.Cart;
import org.beigesoft.webstore.persistable.CuOrSe;
import org.beigesoft.webstore.persistable.CuOrSeGdLn;
import org.beigesoft.webstore.persistable.CuOrSeSrLn;
import org.beigesoft.webstore.persistable.CuOrSeTxLn;
import org.beigesoft.webstore.persistable.CustOrder;
import org.beigesoft.webstore.persistable.CustOrderTxLn;
import org.beigesoft.webstore.persistable.CustOrderSrvLn;
import org.beigesoft.webstore.persistable.CustOrderGdLn;
import org.beigesoft.webstore.persistable.PayMd;
import org.beigesoft.webstore.persistable.SePayMd;
import org.beigesoft.webstore.persistable.OnlineBuyer;
import org.beigesoft.webstore.persistable.SeSeller;
import org.beigesoft.webstore.persistable.SettingsAdd;
import org.beigesoft.webstore.service.ISrvShoppingCart;
import org.beigesoft.webstore.service.IAcpOrd;
import org.beigesoft.webstore.service.ICncOrd;

/**
 * <p>Service that makes orders payed through PayPal.
 * It creates (manages) transactions itself.
 * It makes circle:
 * <ul>
 * <li>phase 1 - accept (book) all buyer's new orders, if OK, then
 * creates PayPal payment, then add payment as "pplPmt" to request attributes
 * to response JSON payment id. If there is order with invoice basis tax
 * method, then all orders will be canceled.
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
 * It must be only record in PAYMD/SEPAYMD table with ITSNAME=PAYPAL that holds
 * MDE="mode", SEC1="clientID" and SEC2="clientSecret".
 * </p>
 *
 * @param <RS> platform dependent record set type
 * @author Yury Demidenko
 */
public class PrPpl<RS> implements IProcessor {

  /**
   * <p>Logger.</p>
   **/
  private ILogger log;

  /**
   * <p>Logger security.</p>
   **/
  private ILogger secLog;

  /**
   * <p>Database service.</p>
   **/
  private ISrvDatabase<RS> srvDb;

  /**
   * <p>ORM service.</p>
   **/
  private ISrvOrm<RS> srvOrm;

  /**
   * <p>Shopping Cart service.</p>
   **/
  private ISrvShoppingCart srvCart;

  /**
   * <p>Accept buyer's new orders service.</p>
   **/
  private IAcpOrd acpOrd;

  /**
   * <p>Cancel accepted buyer's orders service.</p>
   **/
  private ICncOrd cncOrd;

  /**
   * <p>Purchases map:
   * [Buyer ID]p[Purchase ID]t[time in MS]-[PayPal payment ID].
   * For S.E. Seller's purchase there is suffix "s[S.E.Seller ID]" in key</p>
   **/
  private final Map<String, String> pmts = new HashMap<String, String>();

  /**
   * <p>Process entity request.</p>
   * @param pRqVs request scoped vars
   * @param pRqDt Request Data
   * @throws Exception - an exception
   **/
  @Override
  public final void process(final Map<String, Object> pRqVs,
    final IRequestData pRqDt) throws Exception {
    if (!pRqDt.getReqUrl().toString().toLowerCase().startsWith("https")) {
      throw new Exception("PPL http not supported!!!");
    }
    SettingsAdd setAdd = (SettingsAdd) pRqVs.get("setAdd");
    String puid = pRqDt.getParameter("puid");
    chkOutDated(pRqVs, puid, setAdd);
    if (puid != null) {
      String paymentID = this.pmts.get(puid);
      if (paymentID != null) {
        OnlineBuyer buyer = new OnlineBuyer();
        int idxP = puid.indexOf("p");
        int idxT = puid.indexOf("t");
        int idxS = puid.indexOf("s");
        String buyIdStr = puid.substring(0, idxP);
        String purIdStr = puid.substring(idxP + 1, idxT);
        Long prId = Long.parseLong(purIdStr);
        buyer.setItsId(Long.parseLong(buyIdStr));
        Long selId = null;
        if (idxS != -1) {
          selId = Long.parseLong(puid.substring(idxS + 1));
        }
        this.pmts.remove(puid);
        String cnc = pRqDt.getParameter("cnc");
        if (cnc != null) {
          //cancel request:
          try {
            this.srvDb.setIsAutocommit(false);
            this.srvDb.setTransactionIsolation(setAdd.getBkTr());
            this.srvDb.beginTransaction();
            this.cncOrd.cancel(pRqVs, buyer, prId, EOrdStat.BOOKED,
              EOrdStat.NEW);
            this.srvDb.commitTransaction();
          } catch (Exception ex) {
            if (!this.srvDb.getIsAutocommit()) {
              this.srvDb.rollBackTransaction();
            }
            throw ex;
          } finally {
            this.srvDb.releaseResources();
          }
          pRqDt.setAttribute("pplPayId", paymentID);
          pRqDt.setAttribute("pplStat", "canceled");
        } else {
          //phase 2, executing payment:
          String payerID = pRqDt.getParameter("payerID");
          PayMd payMd = null;
          try {
            this.srvDb.setIsAutocommit(false);
            this.srvDb.setTransactionIsolation(setAdd.getBkTr());
            this.srvDb.beginTransaction();
            if (selId == null) {
              List<PayMd> payMds = this.srvOrm.retrieveListWithConditions(pRqVs,
                PayMd.class, "where ITSNAME='PAYPAL'");
              if (payMds.size() == 1) {
                payMd = payMds.get(0);
              }
            } else {
              List<SePayMd> payMds = this.srvOrm.retrieveListWithConditions(
               pRqVs, SePayMd.class, "where ITSNAME='PAYPAL' and SEL=" + selId);
              if (payMds.size() == 1) {
                payMd = payMds.get(0);
              }
            }
            if (!(payerID != null && payMd != null)) {
              this.cncOrd.cancel(pRqVs, buyer, prId, EOrdStat.BOOKED,
                EOrdStat.NEW);
            }
            this.srvDb.commitTransaction();
          } catch (Exception ex) {
            if (!this.srvDb.getIsAutocommit()) {
              this.srvDb.rollBackTransaction();
            }
            throw ex;
          } finally {
            this.srvDb.releaseResources();
          }
          if (payerID != null && payMd != null) {
            try {
              APIContext apiCon = new APIContext(payMd.getMde(),
                payMd.getSec1(), payMd.getSec2());
              Payment pay = new Payment();
              pay.setId(paymentID);
              PaymentExecution payExec = new PaymentExecution();
              payExec.setPayerId(payerID);
              pay.execute(apiCon, payExec);
              pRqDt.setAttribute("pplPayId", pay.getId());
              pRqDt.setAttribute("pplStat", "executed");
            } catch (Exception e) {
              this.cncOrd.cancel(pRqVs, buyer, prId, EOrdStat.BOOKED,
                EOrdStat.NEW);
              throw e;
            }
            try {
              this.srvDb.setIsAutocommit(false);
              this.srvDb.setTransactionIsolation(setAdd.getBkTr());
              this.srvDb.beginTransaction();
              if (selId == null) {
                List<CustOrder> ords = this.srvOrm.retrieveListWithConditions(
                  pRqVs, CustOrder.class, "where PAYMETH in(9,10) and BUYER="
                    + buyIdStr + " and PUR=" + purIdStr);
                for (CustOrder or : ords) {
                  or.setStat(EOrdStat.PAYED);
                  this.srvOrm.updateEntity(pRqVs, or);
                }
              } else {
                List<CuOrSe> ords = this.srvOrm.retrieveListWithConditions(
                  pRqVs, CuOrSe.class, "where PAYMETH in(9,10) and BUYER="
                    + buyIdStr + " and PUR=" + purIdStr + " and SEL=" + selId);
                for (CuOrSe or : ords) {
                  or.setStat(EOrdStat.PAYED);
                  this.srvOrm.updateEntity(pRqVs, or);
                }
              }
              this.srvCart.emptyCart(pRqVs, buyer);
              this.srvDb.commitTransaction();
            } catch (Exception ex) {
              if (!this.srvDb.getIsAutocommit()) {
                this.srvDb.rollBackTransaction();
              }
              throw ex;
            } finally {
              this.srvDb.releaseResources();
            }
          } else {
            throw new Exception("Can't execute PPL payment!");
          }
        }
      }
    } else {
      //phase 1, creating payment:
      phase1(pRqVs, pRqDt, setAdd);
    }
  }

  /**
   * <p>It makes phase 1.</p>
   * @param pRqVs request scoped vars
   * @param pRqDt Request Data
   * @param pSetAdd SettingsAdd
   * @throws Exception - an exception
   **/
  public final void phase1(final Map<String, Object> pRqVs,
    final IRequestData pRqDt, final SettingsAdd pSetAdd) throws Exception {
    String wherePpl = "where ITSNAME='PAYPAL'";
    //phase 1, creating payment:
    List<PayMd> payMds = null;
    List<SePayMd> payMdsSe = null;
    Cart cart = null;
    try {
      this.srvDb.setIsAutocommit(false);
      this.srvDb.setTransactionIsolation(pSetAdd.getBkTr());
      this.srvDb.beginTransaction();
      cart = this.srvCart.getShoppingCart(pRqVs, pRqDt, false);
      if (cart != null && cart.getErr()) {
        cart = null;
      } else if (cart != null) {
        payMds = this.srvOrm.retrieveListWithConditions(pRqVs,
          PayMd.class, wherePpl);
        payMdsSe = this.srvOrm.retrieveListWithConditions(pRqVs,
          SePayMd.class, wherePpl);
      }
      if (cart != null) {
        Purch pur = this.acpOrd.accept(pRqVs, pRqDt, cart.getBuyer());
        CustOrder ord = null;
        //CuOrSe sord = null;
        SeSeller sel = null;
        List<CustOrder> ppords = null;
        List<CuOrSe> ppsords = null;
        if (pur != null) {
          if (pur.getOrds() != null && pur.getOrds().size() > 0) {
            //checking orders with PayPal payment:
            for (CustOrder or : pur.getOrds()) {
              if (or.getPayMeth().equals(EPaymentMethod.PAYPAL)
                || or.getPayMeth().equals(EPaymentMethod.PAYPAL_ANY)) {
                if (ppords == null) {
                  ppords = new ArrayList<CustOrder>();
                }
                ppords.add(or);
              }
            }
          }
          if (pur.getSords() != null && pur.getSords().size() > 0) {
            //checking S.E. orders with PayPal payment:
            for (CuOrSe or : pur.getSords()) {
              if (or.getPayMeth().equals(EPaymentMethod.PAYPAL)
                || or.getPayMeth().equals(EPaymentMethod.PAYPAL_ANY)) {
                if (ppsords == null) {
                  ppsords = new ArrayList<CuOrSe>();
                  sel = or.getSel();
                } else if (!sel.getItsId().getItsId()
                  .equals(or.getSel().getItsId().getItsId())) {
                  throw new Exception("Several S.E.Payee in purchase!");
                }
                ppsords.add(or);
              }
            }
          }
        }
        PayMd payMd = null;
        if (!(ppords != null && ppords.size() > 0
          && ppsords != null && ppsords.size() > 0)) {
          if (ppords != null && ppords.size() > 0) {
            //proceed PayPal orders:
            if (payMds.size() != 1) {
              throw new Exception("There is no properly PPL PayMd");
            } else {
              payMd = payMds.get(0);
            }
            ord = makePplOrds(pRqVs, pRqDt, ppords, cart, CustOrderGdLn.class,
              CustOrderSrvLn.class, CustOrderTxLn.class);
            ord.setCurr(ppords.get(0).getCurr());
            ord.setPur(ppords.get(0).getPur());
          } else if (ppsords != null && ppsords.size() > 0) {
            //proceed PayPal S.E. orders:
            SePayMd payMdSe = null;
            for (SePayMd pm : payMdsSe) {
              if (payMdSe == null && sel.getItsId().getItsId()
                .equals(pm.getSeller().getItsId().getItsId())) {
                payMdSe = pm;
                payMd = pm;
              } else if (payMdSe != null && payMdSe.getSeller().getItsId()
                .getItsId().equals(pm.getSeller().getItsId().getItsId())) {
                throw new Exception(
                  "There is no properly PPL SePayMd for seller#"
                    + sel.getItsId().getItsId());
              }
            }
            ord = makePplOrds(pRqVs, pRqDt, ppsords, cart, CuOrSeGdLn.class,
              CuOrSeSrLn.class, CuOrSeTxLn.class);
            ord.setCurr(ppsords.get(0).getCurr());
            ord.setPur(ppsords.get(0).getPur());
          }
        }
        if (ord != null) {
          createPay(pRqVs, pRqDt, ord, payMd, sel);
        } else {
          throw new Exception("Can't create PPL payment!");
        }
      }
      this.srvDb.commitTransaction();
    } catch (Exception ex) {
      if (!this.srvDb.getIsAutocommit()) {
        this.srvDb.rollBackTransaction();
      }
      throw ex;
    } finally {
      this.srvDb.releaseResources();
    }
  }

  /**
   * <p>It checks for outdated booked orders (20min) and cancels them.</p>
   * @param pRqVs request scoped vars
   * @param pPuId purchase ID, maybe NULL
   * @param pSetAdd SettingsAdd
   * @throws Exception - an exception
   **/
  public final void chkOutDated(final Map<String, Object> pRqVs,
    final String pPuId, final SettingsAdd pSetAdd) throws Exception {
    long now = new Date().getTime();
    List<OnlineBuyer> brs = null;
    List<Long> prs = null;
    for (String puid : this.pmts.keySet()) {
      if (pPuId != null && !pPuId.equals(puid)) {
        int idxT = puid.indexOf("t");
        int idxS = puid.indexOf("s");
        long puTi;
        if (idxS == -1) {
          puTi = Long.parseLong(puid.substring(idxS + 1));
        } else {
          puTi = Long.parseLong(puid.substring(idxS + 1, idxS));
        }
        if (now - puTi > 72000000) {
          getSecLog().warn(pRqVs, PrPpl.class, "Outdated purchase: " + puid);
          int idxP = puid.indexOf("p");
          Long buyerId = Long.parseLong(puid.substring(0, idxP));
          Long prId = Long.parseLong(puid.substring(idxP + 1, idxT));
          OnlineBuyer buyer = new OnlineBuyer();
          buyer.setItsId(buyerId);
          if (brs == null) {
            brs = new ArrayList<OnlineBuyer>();
            prs = new ArrayList<Long>();
          }
          brs.add(buyer);
          prs.add(prId);
        }
      }
    }
    if (brs != null) {
      try {
        this.srvDb.setIsAutocommit(false);
        this.srvDb.setTransactionIsolation(pSetAdd.getBkTr());
        this.srvDb.beginTransaction();
        for (int i = 0; i < brs.size(); i++) {
          this.cncOrd.cancel(pRqVs, brs.get(i), prs.get(i),
            EOrdStat.BOOKED, EOrdStat.NEW);
        }
        this.srvDb.commitTransaction();
      } catch (Exception ex) {
        if (!this.srvDb.getIsAutocommit()) {
          this.srvDb.rollBackTransaction();
        }
        throw ex;
      } finally {
        this.srvDb.releaseResources();
      }
    }
  }

  /**
   * <p>Makes consolidate order with  webstore owner's items.</p>
   * @param <GL> good line type
   * @param <SL> service line type
   * @param <TL> tax line type
   * @param pRqVs request scoped vars
   * @param pRqDt request data
   * @param pPplOrds orders
   * @param pCart cart
   * @param pGlCl good line class
   * @param pSlCl service line class
   * @param pTlCl tax line class
   * @return consolidated order or null if not possible
   * @throws Exception - an exception
   **/
  public final <GL extends AOrdLn, SL extends AOrdLn, TL extends ATaxLn>
   CustOrder makePplOrds(final Map<String, Object> pRqVs,
    final IRequestData pRqDt, final List<? extends IHasIdLongVersion> pPplOrds,
     final Cart pCart, final Class<GL> pGlCl, final Class<SL> pSlCl,
      final Class<TL> pTlCl) throws Exception {
    CustOrder ord = null;
    StringBuffer ordIds = new StringBuffer();
    for (int i = 0; i < pPplOrds.size();  i++) {
      if (i == 0) {
        ordIds.append(pPplOrds.get(i).getItsId().toString());
      } else {
        ordIds.append("," + pPplOrds.get(i).getItsId());
      }
    }
    List<CustOrderGdLn> goods = null;
    List<CustOrderSrvLn> servs = null;
    //checking invoice basis tax:
    Set<String> ndFl = new HashSet<String>();
    ndFl.add("itsId");
    String tbn = pTlCl.getSimpleName();
    pRqVs.put(tbn + "neededFields", ndFl);
    List<TL> ibtxls = this.srvOrm.retrieveListWithConditions(
      pRqVs, pTlCl, "where TAXAB>0 and ITSOWNER in ("
        + ordIds.toString() + ")");
    pRqVs.remove(tbn + "neededFields");
    if (ibtxls.size() == 0) {
      ndFl.add("itsName");
      ndFl.add("price");
      ndFl.add("quant");
      ndFl.add("subt");
      ndFl.add("tot");
      ndFl.add("totTx");
      tbn = pGlCl.getSimpleName();
      pRqVs.put(tbn + "neededFields", ndFl);
      List<GL> gls = this.srvOrm.retrieveListWithConditions(pRqVs,
        pGlCl, "where ITSOWNER in (" + ordIds.toString() + ")");
      pRqVs.remove(tbn + "neededFields");
      if (gls.size() > 0) {
        if (pGlCl == CustOrderGdLn.class) {
          goods = (List<CustOrderGdLn>) gls;
        } else {
          goods = new ArrayList<CustOrderGdLn>();
          for (GL il : gls) {
            CustOrderGdLn itm = new CustOrderGdLn();
            itm.setItsId(il.getItsId());
            itm.setItsName(il.getItsName());
            itm.setPrice(il.getPrice());
            itm.setQuant(il.getQuant());
            itm.setSubt(il.getSubt());
            itm.setTot(il.getTot());
            itm.setTotTx(il.getTotTx());
          }
        }
      }
      tbn = pSlCl.getSimpleName();
      pRqVs.put(tbn + "neededFields", ndFl);
      List<SL> sls = this.srvOrm.retrieveListWithConditions(pRqVs,
        pSlCl, "where ITSOWNER in (" + ordIds.toString() + ")");
      pRqVs.remove(tbn + "neededFields");
      if (sls.size() > 0) {
        if (pSlCl == CustOrderSrvLn.class) {
          servs = (List<CustOrderSrvLn>) sls;
        } else {
          servs = new ArrayList<CustOrderSrvLn>();
          for (SL il : sls) {
            CustOrderSrvLn itm = new CustOrderSrvLn();
            itm.setItsId(il.getItsId());
            itm.setItsName(il.getItsName());
            itm.setPrice(il.getPrice());
            itm.setQuant(il.getQuant());
            itm.setSubt(il.getSubt());
            itm.setTot(il.getTot());
            itm.setTotTx(il.getTotTx());
          }
        }
      }
    } else {
      String err = "Invoice basis PPL!";
      pCart.setErr(true);
      if (pCart.getDescr() == null) {
        pCart.setDescr(err);
      } else {
        pCart.setDescr(pCart.getDescr() + err);
      }
      this.srvOrm.updateEntity(pRqVs, pCart);
    }
    if (goods != null || servs != null) {
      ord = new CustOrder();
      ord.setBuyer(pCart.getBuyer());
      ord.setGoods(goods);
      ord.setServs(servs);
      if (goods != null) {
        for (CustOrderGdLn il : goods) {
          ord.setTot(ord.getTot().add(il.getTot()));
          ord.setTotTx(ord.getTotTx().add(il.getTotTx()));
          ord.setSubt(ord.getSubt().add(il.getSubt()));
        }
      }
      if (servs != null) {
        for (CustOrderSrvLn il : servs) {
          ord.setTot(ord.getTot().add(il.getTot()));
          ord.setTotTx(ord.getTotTx().add(il.getTotTx()));
          ord.setSubt(ord.getSubt().add(il.getSubt()));
        }
      }
    }
    return ord;
  }

  /**
   * <p>Creates PayPal payment out of given orders lines with
   * webstore owner's items.</p>
   * @param pRqVs request scoped vars
   * @param pRqDt request data
   * @param pOrd consolidated order
   * @param pPayMd client codes
   * @param pSel S.E. Seller or NULL
   * @throws Exception - an exception
   **/
  public final void createPay(final Map<String, Object> pRqVs,
    final IRequestData pRqDt, final CustOrder pOrd,
      final PayMd pPayMd, final SeSeller pSel) throws Exception {
    APIContext apiCon = new APIContext(pPayMd.getMde(), pPayMd.getSec1(),
      pPayMd.getSec2());
    Details details = new Details();
    //TODO special headed service "shipping"
    //details.setShipping("1");
    details.setSubtotal(pOrd.getSubt().toString());
    if (pOrd.getTotTx().compareTo(BigDecimal.ZERO) == 1) {
      details.setTax(pOrd.getTotTx().toString());
    }
    Amount amount = new Amount();
    amount.setCurrency(pOrd.getCurr().getStCo());
    amount.setTotal(pOrd.getTot().toString());
    amount.setDetails(details);
    Transaction transaction = new Transaction();
    transaction.setAmount(amount);
    //transaction.setDescription();
    List<Item> items = new ArrayList<Item>();
    if (pOrd.getGoods() != null) {
      for (CustOrderGdLn il : pOrd.getGoods()) {
        Item item = new Item();
        item.setName(il.getItsName());
        item.setQuantity(il.getQuant().toString());
        item.setCurrency(pOrd.getCurr().getStCo());
        item.setPrice(il.getPrice().toString());
        if (il.getTotTx().compareTo(BigDecimal.ZERO) == 1) {
          item.setTax(il.getTotTx().toString());
        }
        items.add(item);
      }
    }
    if (pOrd.getServs() != null) {
      for (CustOrderSrvLn il : pOrd.getServs()) {
        Item item = new Item();
        item.setName(il.getItsName());
        item.setQuantity(il.getQuant().toString());
        item.setCurrency(pOrd.getCurr().getStCo());
        item.setPrice(il.getPrice().toString());
        if (il.getTotTx().compareTo(BigDecimal.ZERO) == 1) {
          item.setTax(il.getTotTx().toString());
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
    String puid = pOrd.getBuyer().getItsId().toString() + "p" + pOrd.getPur()
      + "t" + new Date().getTime();
    if (pSel != null) {
      puid = puid + "s" + pSel.getItsId().getItsId();
    }
    String u = pRqDt.getReqUrl().toString();
    redUrls.setCancelUrl(u + "?nmPrc=PrPpl&nmRnd=ppl&cnc=1&puid=" + puid);
    redUrls.setReturnUrl(u + "?nmPrc=PrPpl&nmRnd=ppl&puid=" + puid);
    payment.setRedirectUrls(redUrls);
    Payment crPay = payment.create(apiCon);
    Iterator<Links> links = crPay.getLinks().iterator();
    while (links.hasNext()) {
      Links link = links.next();
      if (link.getRel().equalsIgnoreCase("approval_url")) {
        pRqDt.setAttribute("redirectURL", link.getHref());
      }
    }
    pRqDt.setAttribute("pplPay", crPay.getId());
    pRqDt.setAttribute("pplStat", "created");
    if (getLog().getIsShowDebugMessagesFor(getClass())
      && getLog().getDetailLevel() > 42000) {
      getLog().debug(pRqVs, PrPpl.class,
        "Cancel URL: " + redUrls.getCancelUrl());
    }
    this.pmts.put(puid, crPay.getId());
  }

  //Simple getters and setters:
  /**
   * <p>Getter for log.</p>
   * @return ILogger
   **/
  public final ILogger getLog() {
    return this.log;
  }

  /**
   * <p>Setter for log.</p>
   * @param pLog reference
   **/
  public final void setLog(final ILogger pLog) {
    this.log = pLog;
  }

  /**
   * <p>Getter for secLog.</p>
   * @return ILogger
   **/
  public final ILogger getSecLog() {
    return this.secLog;
  }

  /**
   * <p>Setter for secLog.</p>
   * @param pSecLog reference
   **/
  public final void setSecLog(final ILogger pSecLog) {
    this.secLog = pSecLog;
  }

  /**
   * <p>Getter for srvDb.</p>
   * @return ISrvDatabase<RS>
   **/
  public final ISrvDatabase<RS> getSrvDb() {
    return this.srvDb;
  }

  /**
   * <p>Setter for srvDb.</p>
   * @param pSrvDb reference
   **/
  public final void setSrvDb(final ISrvDatabase<RS> pSrvDb) {
    this.srvDb = pSrvDb;
  }

  /**
   * <p>Getter for srvOrm.</p>
   * @return ISrvDatabase<RS>
   **/
  public final ISrvOrm<RS> getSrvOrm() {
    return this.srvOrm;
  }

  /**
   * <p>Setter for srvOrm.</p>
   * @param pSrvOrm reference
   **/
  public final void setSrvOrm(final ISrvOrm<RS> pSrvOrm) {
    this.srvOrm = pSrvOrm;
  }

  /**
   * <p>Getter for srvCart.</p>
   * @return ISrvShoppingCart
   **/
  public final ISrvShoppingCart getSrvCart() {
    return this.srvCart;
  }

  /**
   * <p>Setter for srvCart.</p>
   * @param pSrvCart reference
   **/
  public final void setSrvCart(final ISrvShoppingCart pSrvCart) {
    this.srvCart = pSrvCart;
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
}
