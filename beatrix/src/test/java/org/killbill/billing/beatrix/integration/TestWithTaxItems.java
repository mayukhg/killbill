/*
 * Copyright 2014-2016 Groupon, Inc
 * Copyright 2014-2016 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.beatrix.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;

import org.joda.time.LocalDate;
import org.killbill.billing.ObjectType;
import org.killbill.billing.account.api.Account;
import org.killbill.billing.account.api.AccountData;
import org.killbill.billing.api.TestApiListener.NextEvent;
import org.killbill.billing.beatrix.util.InvoiceChecker.ExpectedInvoiceItemCheck;
import org.killbill.billing.catalog.api.BillingActionPolicy;
import org.killbill.billing.catalog.api.BillingPeriod;
import org.killbill.billing.catalog.api.ProductCategory;
import org.killbill.billing.entitlement.api.DefaultEntitlement;
import org.killbill.billing.invoice.api.DryRunType;
import org.killbill.billing.invoice.api.Invoice;
import org.killbill.billing.invoice.api.InvoiceItem;
import org.killbill.billing.invoice.api.InvoiceItemType;
import org.killbill.billing.invoice.model.ExternalChargeInvoiceItem;
import org.killbill.billing.invoice.model.TaxInvoiceItem;
import org.killbill.billing.invoice.plugin.api.InvoicePluginApi;
import org.killbill.billing.osgi.api.OSGIServiceDescriptor;
import org.killbill.billing.osgi.api.OSGIServiceRegistration;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.util.api.TagApiException;
import org.killbill.billing.util.api.TagDefinitionApiException;
import org.killbill.billing.util.callcontext.CallContext;
import org.killbill.billing.util.tag.ControlTagType;
import org.killbill.billing.util.tag.Tag;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableList;

import static org.testng.Assert.assertEquals;

public class TestWithTaxItems extends TestIntegrationBase {

    @Inject
    private OSGIServiceRegistration<InvoicePluginApi> pluginRegistry;

    private TestInvoicePluginApi testInvoicePluginApi;

    @BeforeClass(groups = "slow")
    public void beforeClass() throws Exception {
        super.beforeClass();

        this.testInvoicePluginApi = new TestInvoicePluginApi();
        pluginRegistry.registerService(new OSGIServiceDescriptor() {
            @Override
            public String getPluginSymbolicName() {
                return "TaxInvoicePluginApi";
            }

            @Override
            public String getPluginName() {
                return "TaxInvoicePluginApi";
            }

            @Override
            public String getRegistrationName() {
                return "TaxInvoicePluginApi";
            }
        }, testInvoicePluginApi);
    }

    @BeforeMethod(groups = "slow")
    public void beforeMethod() throws Exception {
        super.beforeMethod();
        testInvoicePluginApi.reset();
    }

    private void add_AUTO_INVOICING_OFF_Tag(final UUID id) throws TagDefinitionApiException, TagApiException {
        busHandler.pushExpectedEvent(NextEvent.TAG);
        tagUserApi.addTag(id, ObjectType.ACCOUNT, ControlTagType.AUTO_INVOICING_OFF.getId(), callContext);
        assertListenerStatus();
        final List<Tag> tags = tagUserApi.getTagsForObject(id, ObjectType.ACCOUNT, false, callContext);
        assertEquals(tags.size(), 1);
    }

    private void remove_AUTO_INVOICING_OFF_Tag(final UUID id, final NextEvent... events) throws TagDefinitionApiException, TagApiException {
        final NextEvent[] allEvents = new NextEvent[events.length + 1];
        allEvents[0] = NextEvent.TAG;
        int i = 1;
        for (NextEvent cur : events) {
            allEvents[i++] = cur;
        }
        busHandler.pushExpectedEvents(allEvents);
        tagUserApi.removeTag(id, ObjectType.ACCOUNT, ControlTagType.AUTO_INVOICING_OFF.getId(), callContext);
        assertListenerStatus();
    }

    @Test(groups = "slow")
    public void testBasicTaxItems() throws Exception {
        // We take april as it has 30 days (easier to play with BCD)
        // Set clock to the initial start date - we implicitly assume here that the account timezone is UTC
        clock.setDay(new LocalDate(2012, 4, 1));

        final AccountData accountData = getAccountData(1);
        final Account account = createAccountWithNonOsgiPaymentMethod(accountData);
        accountChecker.checkAccount(account.getId(), accountData, callContext);

        //
        // Create original subscription (Trial PHASE) -> $0 invoice.
        final DefaultEntitlement bpSubscription = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", "Pistol", ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.INVOICE);
        invoiceChecker.checkInvoice(account.getId(), 1, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        subscriptionChecker.checkSubscriptionCreated(bpSubscription.getId(), internalCallContext);

        // Move to Evergreen PHASE, but add AUTO_INVOICING_OFF => No invoice
        add_AUTO_INVOICING_OFF_Tag(account.getId());
        busHandler.pushExpectedEvent(NextEvent.PHASE);
        clock.addDays(30);
        assertListenerStatus();

        // Add Cleaning ADD_ON => No Invoice
        busHandler.pushExpectedEvents(NextEvent.CREATE, NextEvent.BLOCK);
        addAOEntitlementAndCheckForCompletion(bpSubscription.getBundleId(), "Cleaning", ProductCategory.ADD_ON, BillingPeriod.MONTHLY);
        assertListenerStatus();

        // Make sure TestInvoicePluginApi will return an additional TAX item
        testInvoicePluginApi.addTaxItem(UUID.randomUUID(), new TaxInvoiceItem(UUID.randomUUID(), null, account.getId(), null, "Tax Item", new LocalDate(2012, 5, 1), BigDecimal.ONE, account.getCurrency()));

        // Remove AUTO_INVOICING_OFF => Invoice + Payment
        remove_AUTO_INVOICING_OFF_Tag(account.getId(), NextEvent.INVOICE, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);
        invoiceChecker.checkInvoice(account.getId(), 2, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("2.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), null, InvoiceItemType.TAX, new BigDecimal("1.0")));

        // Add AUTO_INVOICING_OFF and change to a higher plan on the same day that already include the 'Cleaning' ADD_ON, so it gets cancelled
        add_AUTO_INVOICING_OFF_Tag(account.getId());
        busHandler.pushExpectedEvents(NextEvent.CHANGE, NextEvent.CANCEL, NextEvent.BLOCK);
        changeEntitlementAndCheckForCompletion(bpSubscription, "Shotgun", BillingPeriod.MONTHLY, BillingActionPolicy.IMMEDIATE);
        assertListenerStatus();

        // Make sure TestInvoicePluginApi will return an additional TAX item
        testInvoicePluginApi.addTaxItem(UUID.randomUUID(), new TaxInvoiceItem(UUID.randomUUID(), null, account.getId(), null, "Tax Item", new LocalDate(2012, 5, 1), BigDecimal.ONE, account.getCurrency()));

        // Remove AUTO_INVOICING_OFF => Invoice + Payment
        remove_AUTO_INVOICING_OFF_Tag(account.getId(), NextEvent.INVOICE, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);
        invoiceChecker.checkInvoice(account.getId(), 2, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("2.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), null, InvoiceItemType.TAX, new BigDecimal("1.0")));

        invoiceChecker.checkInvoice(account.getId(), 3, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("249.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-29.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-2.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), null, InvoiceItemType.TAX, new BigDecimal("1.0")));

        // Add AUTO_INVOICING_OFF and change to a higher plan on the same day
        add_AUTO_INVOICING_OFF_Tag(account.getId());
        busHandler.pushExpectedEvent(NextEvent.CHANGE);
        changeEntitlementAndCheckForCompletion(bpSubscription, "Assault-Rifle", BillingPeriod.MONTHLY, BillingActionPolicy.IMMEDIATE);
        assertListenerStatus();

        // Make sure TestInvoicePluginApi will return an additional TAX item
        testInvoicePluginApi.addTaxItem(UUID.randomUUID(), new TaxInvoiceItem(UUID.randomUUID(), null, account.getId(), null, "Tax Item", new LocalDate(2012, 5, 1), BigDecimal.ONE, account.getCurrency()));

        // Remove AUTO_INVOICING_OFF => Invoice + Payment
        remove_AUTO_INVOICING_OFF_Tag(account.getId(), NextEvent.INVOICE, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);

        invoiceChecker.checkInvoice(account.getId(), 3, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("249.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-29.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-2.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), null, InvoiceItemType.TAX, new BigDecimal("1.0")));

        invoiceChecker.checkInvoice(account.getId(), 4, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("599.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-249.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), null, InvoiceItemType.TAX, new BigDecimal("1.0")));

    }

    @Test(groups = "slow", description = "https://github.com/killbill/killbill/issues/637")
    public void testDryRunTaxItemsWithCredits() throws Exception {
        // We take april as it has 30 days (easier to play with BCD)
        // Set clock to the initial start date - we implicitly assume here that the account timezone is UTC
        clock.setDay(new LocalDate(2012, 4, 1));

        final AccountData accountData = getAccountData(1);
        final Account account = createAccountWithNonOsgiPaymentMethod(accountData);
        accountChecker.checkAccount(account.getId(), accountData, callContext);

        // Create original subscription (Trial PHASE) -> $0 invoice.
        final DefaultEntitlement bpSubscription = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", "Pistol", ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.INVOICE);
        invoiceChecker.checkInvoice(account.getId(), 1, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        subscriptionChecker.checkSubscriptionCreated(bpSubscription.getId(), internalCallContext);


        busHandler.pushExpectedEvents(NextEvent.INVOICE);
        invoiceUserApi.insertCredit(account.getId(), new BigDecimal("100"), clock.getUTCToday(), account.getCurrency(), true, "VIP", callContext);
        assertListenerStatus();

        invoiceChecker.checkInvoice(account.getId(), 2, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), new LocalDate(2012, 4, 1), InvoiceItemType.CBA_ADJ, new BigDecimal("100")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), new LocalDate(2012, 4, 1), InvoiceItemType.CREDIT_ADJ, new BigDecimal("-100")));

        // Make sure TestInvoicePluginApi will return an additional TAX item
        testInvoicePluginApi.addTaxItem(UUID.randomUUID(), new TaxInvoiceItem(UUID.randomUUID(), null, account.getId(), null, "Tax Item", new LocalDate(2012, 4, 1), BigDecimal.ONE, account.getCurrency()));

        // Verify dry-run scenario
        final Invoice dryRunInvoice = invoiceUserApi.triggerInvoiceGeneration(account.getId(), new LocalDate(2012, 5, 1), new TestDryRunArguments(DryRunType.TARGET_DATE), callContext);
        invoiceChecker.checkInvoiceNoAudits(dryRunInvoice,
                                            ImmutableList.<ExpectedInvoiceItemCheck>of(new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")),
                                                                                       new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.TAX, new BigDecimal("1.0")),
                                                                                       new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), new LocalDate(2012, 4, 1), InvoiceItemType.CBA_ADJ, new BigDecimal("-30.95"))));

        // Make sure TestInvoicePluginApi will return an additional TAX item
        testInvoicePluginApi.addTaxItem(UUID.randomUUID(), new TaxInvoiceItem(UUID.randomUUID(), null, account.getId(), null, "Tax Item", new LocalDate(2012, 5, 1), BigDecimal.ONE, account.getCurrency()));

        // Move to Evergreen PHASE to verify non-dry-run scenario
        busHandler.pushExpectedEvents(NextEvent.PHASE, NextEvent.INVOICE);
        clock.addDays(30);
        assertListenerStatus();

        invoiceChecker.checkInvoice(account.getId(), 3, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), null, InvoiceItemType.TAX, new BigDecimal("1.0")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 5, 1), InvoiceItemType.CBA_ADJ, new BigDecimal("-30.95")));
    }

    @Test(groups = "slow")
    public void testUpdateTaxItems() throws Exception {

        clock.setDay(new LocalDate(2012, 4, 1));

        final AccountData accountData = getAccountData(1);
        final Account account = createAccountWithNonOsgiPaymentMethod(accountData);

        // Create original subscription (Trial PHASE) -> $0 invoice.
        final DefaultEntitlement bpSubscription = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", "Pistol", ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.INVOICE);
        invoiceChecker.checkInvoice(account.getId(), 1, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        subscriptionChecker.checkSubscriptionCreated(bpSubscription.getId(), internalCallContext);

        // Add tags to keep DRAFT invoices and reuse them
        add_AUTO_INVOICING_DRAFT_Tag(account.getId(), ObjectType.ACCOUNT);
        add_AUTO_INVOICING_REUSE_DRAFT_Tag(account.getId(), ObjectType.ACCOUNT);

        // Make sure TestInvoicePluginApi will return an additional TAX item
        final UUID invoiceTaxItemId = UUID.randomUUID();
        testInvoicePluginApi.addTaxItem(invoiceTaxItemId, new TaxInvoiceItem(invoiceTaxItemId, null, account.getId(), null, "Tax Item", new LocalDate(2012, 4, 1), BigDecimal.ONE, account.getCurrency()));

        // Insert external charge autoCommit = false => Invoice will be in DRAFT
        invoiceUserApi.insertExternalCharges(account.getId(), clock.getUTCNow().toLocalDate(), ImmutableList.<InvoiceItem>of(new ExternalChargeInvoiceItem(null, account.getId(), null, "foo", new LocalDate(2012, 4, 1), null, new BigDecimal("33.80"), account.getCurrency())), false, callContext);

        // Make sure TestInvoicePluginApi **update** the original TAX item
        testInvoicePluginApi.addTaxItem(invoiceTaxItemId, new TaxInvoiceItem(invoiceTaxItemId, null, account.getId(), null, "Tax Item", new LocalDate(2012, 4, 1), new BigDecimal("12.45"), account.getCurrency()));

        // Move to Evergreen PHASE, but invoice remains in DRAFT mode
        busHandler.pushExpectedEvents(NextEvent.PHASE /*, NextEvent.INVOICE */);
        clock.addDays(30);
        assertListenerStatus();

        final List<Invoice> accountInvoices = invoiceUserApi.getInvoicesByAccount(account.getId(), false, callContext);
        assertEquals(accountInvoices.size(), 2);

        // Commit invoice
        busHandler.pushExpectedEvents(NextEvent.INVOICE, NextEvent.INVOICE_PAYMENT, NextEvent.PAYMENT);
        invoiceUserApi.commitInvoice(accountInvoices.get(1).getId(), callContext);
        assertListenerStatus();

        invoiceChecker.checkInvoice(account.getId(), 2, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.EXTERNAL_CHARGE, new BigDecimal("33.80")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.TAX, new BigDecimal("12.45")));

    }

    public class TestInvoicePluginApi implements InvoicePluginApi {

        private final Map<UUID, TaxInvoiceItem> taxItems;

        public TestInvoicePluginApi() {
            taxItems = new HashMap<UUID, TaxInvoiceItem>();
        }

        @Override
        public List<InvoiceItem> getAdditionalInvoiceItems(final Invoice invoice, final boolean isDryRun, final Iterable<PluginProperty> pluginProperties, final CallContext callContext) {
            final List<InvoiceItem> result = new ArrayList<InvoiceItem>();
            for (UUID itemId : taxItems.keySet()) {
                final TaxInvoiceItem item = taxItems.get(itemId);
                result.add(new TaxInvoiceItem(item.getId(), invoice.getId(), invoice.getAccountId(), item.getBundleId(), "Tax Item", item.getStartDate(), item.getAmount(), invoice.getCurrency()));
            }
            taxItems.clear();
            return result;
        }

        public void reset() {
            taxItems.clear();
        }

        public void addTaxItem(final UUID invoiceItemId, final TaxInvoiceItem item) {
            taxItems.put(invoiceItemId, item);
        }


    }
}
