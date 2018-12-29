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
import org.beigesoft.webstore.persistable.PayMd;
import org.beigesoft.webstore.persistable.OnlineBuyer;
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
 * It must be only record in PAYMD table with ITSNAME="PAYPAL" that holds
 * MDE="mode", SEC1="clientID" and SEC2="clientSecret".
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
   * <p>Purchases map.</p>
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
    String puid = pRqDt.getParameter("puid");
    if (puid != null) {
      String paymentID = this.pmts.get(puid);
      if (paymentID != null) {
        OnlineBuyer buyer = new OnlineBuyer();
        String buyIdStr = puid.substring(0, puid.indexOf("-"));
        String purIdStr = puid.substring(puid.indexOf("-") + 1);
        Long prId = Long.parseLong(purIdStr);
        buyer.setItsId(Long.parseLong(buyIdStr));
        this.pmts.remove(puid);
        String cnc = pRqDt.getParameter("cnc");
        if (cnc != null) {
          this.cncOrd.cancel(pRqVs, buyer, prId, EOrdStat.BOOKED, EOrdStat.NEW);
        } else {
          //phase 2, executing payment:
          String payerID = pRqDt.getParameter("payerID");
          PayMd payMd = null;
          try {
            this.srvDb.setIsAutocommit(false);
            this.srvDb.setTransactionIsolation(ISrvDatabase
              .TRANSACTION_READ_COMMITTED);
            this.srvDb.beginTransaction();
            List<PayMd> payMds = this.srvOrm.retrieveListWithConditions(pRqVs,
              PayMd.class, "where ITSNAME=PAYPAL");
            if (payMds.size() == 1) {
              payMd = payMds.get(0);
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
              APIContext apiCon = new APIContext(payMd.getMde(), payMd.getSec1(),
                payMd.getSec2());
              Payment pay = new Payment();
              pay.setId(paymentID);
              PaymentExecution payExec = new PaymentExecution();
              payExec.setPayerId(payerID);
              Payment crPay = pay.execute(apiCon, payExec);
            } catch (Exception e) {
              this.cncOrd.cancel(pRqVs, buyer, prId, EOrdStat.BOOKED,
                EOrdStat.NEW);
            }
          } else {
            this.cncOrd.cancel(pRqVs, buyer, prId, EOrdStat.BOOKED, EOrdStat.NEW);
          }
        }
      }
    } else {
      //phase 1, creating payment:
      PayMd payMd = null;
      Cart cart = null;
      try {
        this.srvDb.setIsAutocommit(false);
        this.srvDb.setTransactionIsolation(ISrvDatabase
          .TRANSACTION_READ_COMMITTED);
        this.srvDb.beginTransaction();
        cart = this.srvCart.getShoppingCart(pRqVs, pRqDt, false);
        if (cart.getErr()) {
          cart = null;
        } else {
          List<PayMd> payMds = this.srvOrm.retrieveListWithConditions(pRqVs, PayMd.class,
            "where ITSNAME=PAYPAL");
          if (payMds.size() == 1) {
            payMd = payMds.get(0);
          } else {
            String err = "There is no PPL PayMd!";
            cart.setErr(true);
            if (cart.getDescr() == null) {
              cart.setDescr(err);
            } else {
              cart.setDescr(cart.getDescr() + err);
            }
            this.srvOrm.updateEntity(pRqVs, cart);
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
      if (cart != null) {
        List<CustOrder> ords = null;
        Purch pur = this.acpOrd.accept(pRqVs, pRqDt, cart.getBuyer());
        CustOrder ord = null;
        CuOrSe sord = null;
        List<CustOrder> ppords = null;
        List<CuOrSe> ppsords = null;
        try {
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
                  }
                  ppsords.add(or);
                }
              }
            }
          }
          if (!(ppords != null && ppords.size() > 0
            && ppsords != null && ppsords.size() > 0)) {
            if (ppords != null && ppords.size() > 0) {
              //proceed PayPal orders:
              ord = makePplOrds(pRqVs, pRqDt, ppords, cart);
            } else if (ppsords != null && ppsords.size() > 0) {
              //proceed PayPal S.E. orders:
            }
          }
          if (ord != null) {
            createPay(pRqVs, pRqDt, ord, payMd);
          } else {
            throw new Exception("Can't make PPL!");
          }
        } catch (Exception e) {
          this.cncOrd.cancel(pRqVs, pur, EOrdStat.NEW);
        }
      }
    }
  }

  /**
   * <p>Makes consolidate order with  webstore owner's items.</p>
   * @param pRqVs request scoped vars
   * @param pRqDt request data
   * @param pPplOrds orders
   * @param pCart cart
   * @return consolidated order or null if not possible
   * @throws Exception - an exception
   **/
  public final CustOrder makePplOrds(final Map<String, Object> pRqVs,
    final IRequestData pRqDt, final List<CustOrder> pPplOrds,
      final Cart pCart) throws Exception {
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
        goods = this.srvOrm.retrieveListWithConditions(pRqVs,
          CustOrderGdLn.class, "where ITSOWNER in (" + ordIds.toString() + ")");
        pRqVs.remove(tbn + "neededFields");
        if (goods.size() == 0) {
          goods = null;
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
      this.srvDb.commitTransaction();
    } catch (Exception ex) {
      if (!this.srvDb.getIsAutocommit()) {
        this.srvDb.rollBackTransaction();
      }
      throw ex;
    } finally {
      this.srvDb.releaseResources();
    }
    if (goods != null || servs != null) {
      ord = new CustOrder();
      ord.setBuyer(pCart.getBuyer());
      ord.setCurr(pPplOrds.get(0).getCurr());
      ord.setPur(pPplOrds.get(0).getPur());
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
   * @throws Exception - an exception
   **/
  public final void createPay(final Map<String, Object> pRqVs,
    final IRequestData pRqDt, final CustOrder pOrd,
      final PayMd pPayMd) throws Exception {
    APIContext apiCon = new APIContext(pPayMd.getMde(), pPayMd.getSec1(),
      pPayMd.getSec2());
    Details details = new Details();
    //TODO special headed service "shipping"
    //details.setShipping("1");
    details.setSubtotal(pOrd.getSubt().toString());
    details.setTax(pOrd.getTotTx().toString());
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
        item.setName(il.getGood().getItsName());
        item.setQuantity(il.getQuant().toString());
        item.setCurrency(pOrd.getCurr().getStCo());
        item.setPrice(il.getPrice().toString());
        item.setTax(il.getTotTx().toString());
        items.add(item);
      }
    }
    if (pOrd.getServs() != null) {
      for (CustOrderSrvLn il : pOrd.getServs()) {
        Item item = new Item();
        item.setName(il.getService().getItsName());
        item.setQuantity(il.getQuant().toString());
        item.setCurrency(pOrd.getCurr().getStCo());
        item.setPrice(il.getPrice().toString());
        item.setTax(il.getTotTx().toString());
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
    String puid = pOrd.getBuyer().getItsId().toString() + "-" + pOrd.getPur();
    redUrls.setCancelUrl(pRqDt.getReqUrl() + "?nmPrc=PrPpl&cnc=1&puid=" + puid);
    redUrls.setReturnUrl(pRqDt.getReqUrl() + "?nmPrc=PrPpl&puid=" + puid);
    payment.setRedirectUrls(redUrls);
    Payment crPay = payment.create(apiCon);
    Iterator<Links> links = crPay.getLinks().iterator();
    while (links.hasNext()) {
      Links link = links.next();
      if (link.getRel().equalsIgnoreCase("approval_url")) {
        pRqDt.setAttribute("redirectURL", link.getHref());
      }
    }
    pRqDt.setAttribute("pplPay", crPay);
    this.pmts.put(puid, crPay.getId());
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
