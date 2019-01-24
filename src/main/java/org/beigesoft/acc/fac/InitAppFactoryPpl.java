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

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.io.File;

import com.paypal.api.payments.Item;

import org.beigesoft.delegate.IDelegate;
import org.beigesoft.orm.service.ASrvOrm;
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
 * <p>
 * Initialize app-factory with servlet parameters.
 * </p>
 *
 * @param <RS> platform dependent RDBMS recordset
 * @author Yury Demidenko
 */
public class InitAppFactoryPpl<RS> implements IDelegate<FactoryAndServlet> {

  /**
   * <p>Make something with a model.</p>
   * @param pReqVars additional request scoped parameters
   * @throws Exception - an exception
   * @param pFactoryAndServlet with make
   **/
  @Override
  public final synchronized void makeWith(final Map<String, Object> pReqVars,
    final FactoryAndServlet pFactoryAndServlet) throws Exception {
    @SuppressWarnings("unchecked")
    AFactoryAppBeans<RS> factoryAppBeans =
      (AFactoryAppBeans<RS>) pFactoryAndServlet.getFactoryAppBeans();
    File webAppPath = new File(pFactoryAndServlet.getHttpServlet()
      .getServletContext().getRealPath(""));
    factoryAppBeans.setWebAppPath(webAppPath.getPath());
    String isShowDebugMessagesStr = pFactoryAndServlet.getHttpServlet()
      .getInitParameter("isShowDebugMessages");
    factoryAppBeans.setIsShowDebugMessages(Boolean
      .valueOf(isShowDebugMessagesStr));
    String detailLevelStr = pFactoryAndServlet.getHttpServlet()
      .getInitParameter("detailLevel");
    factoryAppBeans.setDetailLevel(Integer.parseInt(detailLevelStr));
    String newDatabaseIdStr = pFactoryAndServlet.getHttpServlet()
      .getInitParameter("newDatabaseId");
    factoryAppBeans.setNewDatabaseId(Integer.parseInt(newDatabaseIdStr));
    String ormSettingsDir = pFactoryAndServlet.getHttpServlet()
      .getInitParameter("ormSettingsDir");
    factoryAppBeans.setOrmSettingsDir(ormSettingsDir);
    String ormSettingsBaseFile = pFactoryAndServlet.getHttpServlet()
      .getInitParameter("ormSettingsBaseFile");
    factoryAppBeans.setOrmSettingsBaseFile(ormSettingsBaseFile);
    String uvdSettingsDir = pFactoryAndServlet.getHttpServlet()
      .getInitParameter("uvdSettingsDir");
    factoryAppBeans.setUvdSettingsDir(uvdSettingsDir);
    String uvdSettingsBaseFile = pFactoryAndServlet.getHttpServlet()
      .getInitParameter("uvdSettingsBaseFile");
    factoryAppBeans.setUvdSettingsBaseFile(uvdSettingsBaseFile);
    String writeTi = pFactoryAndServlet.getHttpServlet()
      .getInitParameter("writeTi");
    factoryAppBeans.setWriteTi(Integer.parseInt(writeTi));
    String readTi = pFactoryAndServlet.getHttpServlet()
      .getInitParameter("readTi");
    factoryAppBeans.setReadTi(Integer.parseInt(readTi));
    String writeReTi = pFactoryAndServlet.getHttpServlet()
      .getInitParameter("writeReTi");
    factoryAppBeans.setWriteReTi(Integer.parseInt(writeReTi));
    String wrReSpTr = pFactoryAndServlet.getHttpServlet()
      .getInitParameter("wrReSpTr");
    factoryAppBeans.setWrReSpTr(Boolean.valueOf(wrReSpTr));
    String fastLoc = pFactoryAndServlet.getHttpServlet()
      .getInitParameter("fastLoc");
    factoryAppBeans.setFastLoc(Boolean.parseBoolean(fastLoc));
    String jdbcUrl = pFactoryAndServlet.getHttpServlet()
      .getInitParameter("databaseName");
    if (jdbcUrl != null && jdbcUrl.contains(ASrvOrm.WORD_CURRENT_DIR)) {
      jdbcUrl = jdbcUrl.replace(ASrvOrm.WORD_CURRENT_DIR,
        factoryAppBeans.getWebAppPath() + File.separator);
    } else if (jdbcUrl != null
      && jdbcUrl.contains(ASrvOrm.WORD_CURRENT_PARENT_DIR)) {
      File fcd = new File(factoryAppBeans.getWebAppPath());
      jdbcUrl = jdbcUrl.replace(ASrvOrm.WORD_CURRENT_PARENT_DIR,
        fcd.getParent() + File.separator);
    }
    String langCountriesStr = pFactoryAndServlet.getHttpServlet()
      .getInitParameter("langCountries");
    List<String> lngCntLst = new ArrayList<String>();
    for (String str : langCountriesStr.split(",")) {
      lngCntLst.add(str);
    }
    String[] lngCntArr = new String[lngCntLst.size()];
    factoryAppBeans.lazyGetSrvI18n().add(lngCntLst.toArray(lngCntArr));
    //ordinal JEE WEB application is not designed to change database
    //so it will never happen:
    LstnDbChangedPpl<RS> lstnDbChanged = new LstnDbChangedPpl<RS>();
    lstnDbChanged.setFactoryAndServlet(pFactoryAndServlet);
    factoryAppBeans.getListenersDbChanged().add(lstnDbChanged);
    factoryAppBeans.setDatabaseName(jdbcUrl);
    String databaseUser = pFactoryAndServlet.getHttpServlet()
      .getInitParameter("databaseUser");
    factoryAppBeans.setDatabaseUser(databaseUser);
    String databasePassword = pFactoryAndServlet.getHttpServlet()
      .getInitParameter("databasePassword");
    factoryAppBeans.setDatabasePassword(databasePassword);
    pFactoryAndServlet.getHttpServlet().getServletContext()
      .setAttribute("srvI18n", factoryAppBeans.lazyGet("ISrvI18n"));
    pFactoryAndServlet.getHttpServlet().getServletContext()
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
    //it create/initialize database if need:
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
}
