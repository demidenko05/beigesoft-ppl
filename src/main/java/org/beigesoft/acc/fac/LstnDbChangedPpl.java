package org.beigesoft.acc.fac;

/*
 * Copyright (c) 2019 Beigesoft™
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

import com.paypal.api.payments.Item;

import org.beigesoft.delegate.IDelegator;
import org.beigesoft.web.model.FactoryAndServlet;
import org.beigesoft.web.factory.AFactoryAppBeans;
import org.beigesoft.accounting.factory.FactoryAccServices;
import org.beigesoft.accounting.service.ISrvAccSettings;
import org.beigesoft.accounting.service.HndlAccVarsRequest;
import org.beigesoft.webstore.service.HndlTradeVarsRequest;
import org.beigesoft.webstore.service.ISrvTradingSettings;
import org.beigesoft.webstore.service.UtlTradeJsp;
import org.beigesoft.webstore.service.ISrvSettingsAdd;
import org.beigesoft.webstore.service.SrvShoppingCart;
import org.beigesoft.ppl.PrPpl;

/**
 * <p>Re-initializes external context after database
 * has been changed (i.e. switch from a.sqlite to b.sqlite).</p>
 *
 * @param <RS> platform dependent RDBMS recordset
 * @author Yury Demidenko
 */
public class LstnDbChangedPpl<RS> implements IDelegator {

  /**
   * <p>Factory and servlet bundle.</p>
   **/
  private FactoryAndServlet factoryAndServlet;

  /**
   * <p>Make something with a model.</p>
   * @param pReqVars additional request scoped parameters
   * @throws Exception - an exception
   * @param pFactoryAppBeans with make
   **/
  @Override
  public final void make(final Map<String, Object> pReqVars) throws Exception {
    @SuppressWarnings("unchecked")
    AFactoryAppBeans<RS> factoryAppBeans =
      (AFactoryAppBeans<RS>) this.factoryAndServlet.getFactoryAppBeans();
    this.factoryAndServlet.getHttpServlet().getServletContext()
      .setAttribute("srvI18n", factoryAppBeans.lazyGet("ISrvI18n"));
    this.factoryAndServlet.getHttpServlet().getServletContext()
      .setAttribute("sessionTracker",
        factoryAppBeans.lazyGet("ISessionTracker"));
    HndlTradeVarsRequest<RS> hndlTradeVarsRequest =
      new HndlTradeVarsRequest<RS>();
    hndlTradeVarsRequest.setLogger(factoryAppBeans.lazyGetLogger());
    hndlTradeVarsRequest.setSrvDatabase(factoryAppBeans.lazyGetSrvDatabase());
    hndlTradeVarsRequest.setSrvOrm(factoryAppBeans.lazyGetSrvOrm());
    hndlTradeVarsRequest.setUtlTradeJsp((UtlTradeJsp)
      factoryAppBeans.lazyGet("utlTradeJsp"));
    hndlTradeVarsRequest.setSrvSettingsAdd((ISrvSettingsAdd)
      factoryAppBeans.lazyGet("ISrvSettingsAdd"));
    hndlTradeVarsRequest.setSrvTradingSettings((ISrvTradingSettings)
      factoryAppBeans.lazyGet("ISrvTradingSettings"));
    HndlAccVarsRequest<RS> hndlAccVarsRequest = new HndlAccVarsRequest<RS>();
    hndlAccVarsRequest.setAdditionalI18nReqHndl(hndlTradeVarsRequest);
    hndlAccVarsRequest.setLogger(factoryAppBeans.lazyGetLogger());
    hndlAccVarsRequest.setSrvDatabase(factoryAppBeans.lazyGetSrvDatabase());
    hndlAccVarsRequest.setSrvOrm(factoryAppBeans.lazyGetSrvOrm());
    hndlAccVarsRequest.setSrvAccSettings((ISrvAccSettings) factoryAppBeans
      .lazyGet("ISrvAccSettings"));
    factoryAppBeans.lazyGetHndlI18nRequest()
      .setAdditionalI18nReqHndl(hndlAccVarsRequest);
    PrPpl<RS> prPpl = new PrPpl<RS>();
    FactoryAccServices<RS> fas = (FactoryAccServices<RS>) factoryAppBeans
      .getFactoryOverBeans();
    prPpl.setSrvOrm(factoryAppBeans.lazyGetSrvOrm());
    prPpl.setSrvDb(factoryAppBeans.lazyGetSrvDatabase());
    prPpl.setSecLog(factoryAppBeans.lazyGetSecureLogger());
    prPpl.setSrvNumToStr(factoryAppBeans.lazyGetSrvNumberToString());
    prPpl.setLog(factoryAppBeans.lazyGetLogger());
    SrvShoppingCart<RS> sc = fas.lazyGetSrvShoppingCart();
    sc.setPplCl(Item.class);
    prPpl.setSrvCart(sc);
    prPpl.setAcpOrd(fas.lazyGetAcpOrd());
    prPpl.setCncOrd(fas.lazyGetCncOrd());
    prPpl.setBuySr(fas.lazyGetBuySr());
    prPpl.setSpamHnd(fas.lazyGetSpamHnd());
    factoryAppBeans.getBeansMap().put("PrPpl", prPpl);
  }

  //Simple getters and setters:

  /**
   * <p>Getter for factoryAndServlet.</p>
   * @return FactoryAndServlet
   **/
  public final FactoryAndServlet getFactoryAndServlet() {
    return this.factoryAndServlet;
  }

  /**
   * <p>Setter for factoryAndServlet.</p>
   * @param pFactoryAndServlet reference
   **/
  public final void setFactoryAndServlet(
    final FactoryAndServlet pFactoryAndServlet) {
    this.factoryAndServlet = pFactoryAndServlet;
  }
}
