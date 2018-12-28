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
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
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
import com.paypal.base.rest.PayPalRESTException;

import org.beigesoft.model.IRequestData;
import org.beigesoft.service.IProcessor;
import org.beigesoft.service.ISrvOrm;
import org.beigesoft.service.ISrvDatabase;
import org.beigesoft.webstore.model.EOrdStat;
import org.beigesoft.webstore.model.EPaymentMethod;
import org.beigesoft.webstore.model.Purch;
import org.beigesoft.webstore.persistable.Cart;
import org.beigesoft.webstore.persistable.CuOrSe;
import org.beigesoft.webstore.persistable.CustOrder;
import org.beigesoft.webstore.persistable.CustOrderTxLn;
import org.beigesoft.webstore.persistable.CustOrderSrvLn;
import org.beigesoft.webstore.persistable.CustOrderGdLn;
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
 * canceling request in this case.
 * This is processor only for webstore owner's orders. That is it must not be
 * several online payee in same purchase, e.g. order with WS owner's items and
 * PayPal method, and order with S.E.Seller1 items and PayPal method.
 * </p>
 *
 * @param <RS> platform dependent record set type
 * @author Yury Demidenko
 */
public class PrPpl<RS> implements IProcessor {

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
   * <p>Process entity request.</p>
   * @param pRqVs request scoped vars
   * @param pRqDt Request Data
   * @throws Exception - an exception
   **/
  @Override
  public final void process(final Map<String, Object> pRqVs,
    final IRequestData pRqDt) throws Exception {
    Cart cart = null;
    try {
      this.srvDb.setIsAutocommit(false);
      this.srvDb.setTransactionIsolation(ISrvDatabase
        .TRANSACTION_READ_COMMITTED);
      this.srvDb.beginTransaction();
      cart = this.srvCart.getShoppingCart(pRqVs, pRqDt, false);
      this.srvDb.commitTransaction();
    } catch (Exception ex) {
      if (!this.srvDb.getIsAutocommit()) {
        this.srvDb.rollBackTransaction();
      }
      throw ex;
    } finally {
      this.srvDb.releaseResources();
    }    
    if (cart != null) {
      List<CustOrder> ords = null;
      String payerID = pRqDt.getParameter("payerID");
      if (payerID == null) {
        //phase 1, creating payment:
        Purch pur = this.acpOrd.accept(pRqVs, pRqDt, cart.getBuyer());
        boolean isProceeded = false;
        List<CustOrder> ppords = null;
        List<CuOrSe> ppsords = null;
        if (pur != null && pur.getOrds() != null && pur.getOrds().size() > 0) {
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
        if (!(ppords != null && ppords.size() > 0
          && ppsords != null && ppsords.size() > 0)) {
          if (ppords != null && ppords.size() > 0) {
            //proceed PayPal orders:
            isProceeded = makePplOrds(pRqVs, pRqDt, ppords);
          } else if (ppsords != null && ppsords.size() > 0) {
            //proceed PayPal S.E. orders:
          }
        }
        if (!isProceeded) {
          this.cncOrd.cancel(pRqVs, ords, EOrdStat.NEW);
        }
      } else {
        //phase 2, executing payment:
        APIContext apiCon = new APIContext("", "", "");
        String paymentID = pRqDt.getParameter("paymentID");
        Payment pay = new Payment();
        pay.setId(paymentID);
        PaymentExecution payExec = new PaymentExecution();
        payExec.setPayerId(payerID);
        try {
          Payment crPay = pay.execute(apiCon, payExec);
        } catch (PayPalRESTException e) {
          this.cncOrd.cancel(pRqVs, cart.getBuyer(),
            EOrdStat.BOOKED, EOrdStat.NEW);
        }
      }
    }
  }

  /**
   * <p>Makes consolidate order with PayPal payment and
   * webstore owner's items and create PayPal payment.</p>
   * @param pRqVs request scoped vars
   * @param pRqDt request data
   * @param pPplOrds orders
   * @return if proceeded
   * @throws Exception - an exception
   **/
  public final boolean makePplOrds(final Map<String, Object> pRqVs,
    final IRequestData pRqDt, final List<CustOrder> pPplOrds) throws Exception {
    boolean isPplErr = false;
    StringBuffer ordIds = new StringBuffer();
    for (int i = 0; i < pPplOrds.size();  i++) {
      if (i == 0) {
        ordIds.append(pPplOrds.get(i).getItsId().toString());
      } else {
        ordIds.append("," + pPplOrds.get(i).getItsId());
      }
    }
    try {
      this.srvDb.setIsAutocommit(false);
      this.srvDb.setTransactionIsolation(ISrvDatabase
        .TRANSACTION_READ_COMMITTED);
      this.srvDb.beginTransaction();
      //checking invoice basis tax:
      Set<String> ndFl = new HashSet<String>();
      ndFl.add("itsId");
      String tbn = CustOrderTxLn.class.getSimpleName();
      pRqVs.put(tbn + "neededFields", ndFl);
      List<CustOrderTxLn> ibtxls = this.srvOrm.retrieveListWithConditions(
        pRqVs, CustOrderTxLn.class, "where TAXAB>0 and ITSOWNER in ("
          + ordIds.toString() + ")");
      pRqVs.remove(tbn + "neededFields");
      if (ibtxls.size() == 0) {
        ndFl.add("good");
        tbn = CustOrderGdLn.class.getSimpleName();
        pRqVs.put(tbn + "neededFields", ndFl);
        List<CustOrderGdLn> gls = this.srvOrm.retrieveListWithConditions(
          pRqVs, CustOrderGdLn.class, "where ITSOWNER in ("
            + ordIds.toString() + ")");
        pRqVs.remove(tbn + "neededFields");
      } else {
        isPplErr = true;
      }
      if (isPplErr) {
        this.srvDb.rollBackTransaction();
      } else {
        this.srvDb.commitTransaction();
      }
    } catch (Exception ex) {
      if (!this.srvDb.getIsAutocommit()) {
        this.srvDb.rollBackTransaction();
      }
      throw ex;
    } finally {
      this.srvDb.releaseResources();
    }
    return !isPplErr;
  }

  /**
   * <p>Creates PayPal payment out of given orders lines with
   * webstore owner's items.</p>
   * @param pRqVs request scoped vars
   * @param pRqDt request data
   * @param pGoods Goods lines
   * @param pServs Serves lines
   * @throws Exception - an exception
   **/
  public final void createPay(final Map<String, Object> pRqVs,
    final IRequestData pRqDt, final List<CustOrderGdLn> pGoods,
      final List<CustOrderSrvLn> pServs) throws Exception {
    //TODO special headed service "shipping"
  }

  //Simple getters and setters:
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
