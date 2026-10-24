const path = require('node:path');
const {createRequire} = require('node:module');
const configRequire = createRequire(path.resolve(__dirname, '../../../SupportConfigFiles/package.json'));
const {test, expect} = configRequire('@playwright/test');

const toppings = [
    {toppingId: 1, name: 'Cheese'}, {toppingId: 2, name: 'Zulu'},
    {toppingId: 3, name: 'Apple'}, {toppingId: 4, name: 'Basil'},
    {toppingId: 5, name: 'Unknown'}, {toppingId: 6, name: 'Mushroom'}
];
const priceFor = (id, size) => ({2: size === 23 ? 7 : 5, 3: size === 23 ? 3 : 5, 4: 0, 6: size === 23 ? 7 : 10})[id];
const menuItems = [
    {menuItemId: 701, name: 'Test Burger', categoryId: 1, categoryName: 'Burgers', price: 80,
        modifierGroups: [], burgerToppings: [
            {toppingId: 101, name: 'Beef', componentType: 'protein', price: 0},
            {toppingId: 102, name: 'Lettuce', componentType: 'default_topping', defaultSelected: true, removable: true, price: 0},
            {toppingId: 103, name: 'Bacon', componentType: 'extra_topping', price: 10}
        ]},
    {menuItemId: 702, name: 'Test Pasta', categoryId: 2, categoryName: 'Pasta', price: 65,
        modifierGroups: [{groupId: 501, name: 'Sauce', required: true, minSelect: 1, maxSelect: 2,
            options: [{optionId: 601, name: 'Tomato sauce', price: 3}, {optionId: 602, name: 'Cream sauce', price: 5}]}]}
];
const special = {id: 801, name: 'Test Special', price: 150, availableToday: true, availableDays: [1, 2, 3, 4, 5, 6, 7],
    components: [{id: 901, label: 'Pizza', quantity: 1, selectionMode: 'CHOICE', productType: 'PIZZA',
        sizeCm: 30, allowCustomization: true, options: [{id: 901, name: 'Test Pizza'}]}], addons: []};

async function mockCatalog(page) {
    const control = {quoteFails: false, specialFails: false, detailGate: null};
    await page.route('**/api/branches/**', async route => {
        const url = new URL(route.request().url());
        let json;
        if (/\/pizzas$/.test(url.pathname)) {
            json = {favouritePizzas: [{pizzaId: 901, name: 'Test Pizza', startingPrice: 80}], supremePizzas: []};
        } else if (/\/pizzas\/901\/quote$/.test(url.pathname)) {
            if (control.quoteFails) return route.fulfill({status: 400, body: 'Quote unavailable'});
            const payload = route.request().postDataJSON();
            const basePrice = payload.sizeCm === 23 ? 80 : 100;
            const toppingsTotal = payload.selectedToppingIds.filter(id => id !== 1)
                .reduce((sum, id) => sum + (priceFor(id, payload.sizeCm) ?? 12), 0);
            json = {basePrice, baseOptionTotal: 0, toppingsTotal, total: basePrice + toppingsTotal};
        } else if (/\/pizzas\/901$/.test(url.pathname)) {
            if (control.detailGate) await control.detailGate;
            const size = Number(url.searchParams.get('sizeCm') || 23);
            json = {pizzaId: 901, name: 'Test Pizza', availableSizes: [{sizeCm: 23, basePrice: 80}, {sizeCm: 30, basePrice: 100}],
                defaultToppings: [{...toppings[0], removable: true}], allToppings: toppings,
                extraToppingGroups: [
                    {name: 'Category one', toppings: [toppings[1], toppings[3], toppings[5], toppings[0]]},
                    {name: 'Category two', toppings: [toppings[2], toppings[4], toppings[1]]}
                ], extraPrices: [2, 3, 4, 6].map(id => ({toppingId: id, extraPrice: priceFor(id, size)})), baseOptions: []};
        } else if (/\/menu-items$/.test(url.pathname)) json = menuItems;
        else if (/\/specials\/week$/.test(url.pathname)) json = [special];
        else if (/\/specials\/801\/quote$/.test(url.pathname)) {
            if (control.specialFails) return route.fulfill({status: 400, body: 'Special unavailable'});
            json = {basePrice: 140, customizationTotal: 5, addonTotal: 5, lineTotal: 150 * route.request().postDataJSON().quantity};
        } else return route.continue();
        await route.fulfill({json});
    });
    return control;
}

async function login(page) {
    await page.goto('/login');
    await page.locator('#username').fill('browser@example.com');
    await page.locator('#password').fill('Browser-test-123');
    await page.getByRole('button', {name: 'Login', exact: true}).click();
    await expect(page).not.toHaveURL(/\/login/);
}

async function startOrder(page) {
    await login(page);
    const control = await mockCatalog(page);
    await page.goto('/order');
    if (await page.locator('#branchKenridge').isVisible()) await page.locator('#branchKenridge').click();
    await expect(page.locator('#listBody .row').filter({hasText: 'Test Pizza'})).toBeVisible();
    return control;
}

async function openPizza(page) {
    const quote = page.waitForResponse(response => /\/pizzas\/901\/quote$/.test(new URL(response.url()).pathname));
    await page.locator('#listBody .row').filter({hasText: 'Test Pizza'}).click();
    await quote;
    await expect(page.locator('#size_30')).toBeChecked();
    await expect(page.locator('#stickyTotal')).toHaveText('R100.00');
}

async function groupNames(page) {
    return page.locator('.topping-price-group').evaluateAll(groups => groups.map(group =>
        [...group.querySelectorAll('label')].map(label => label.textContent)));
}

for (const [route, name] of [['/menu/kenridge', 'Kenridge'], ['/menu/uitzicht', 'Uitzicht']]) {
    test(`${name} branch badge contains only its name`, async ({page}) => {
        await mockCatalog(page);
        await page.goto(route);
        await expect(page.locator('#branchPill')).toHaveText(name);
        await expect(page.locator('#branchLabel')).toHaveText(name);
        await expect(page.locator('.top-actions .pill')).toHaveText(name);
        await expect(page.locator('.top-actions .dot, #cartPill')).toHaveCount(0);
    });
}

test('pizza extras merge by price, have unlabelled gaps, and regroup without losing selections', async ({page}) => {
    await startOrder(page);
    await openPizza(page);
    expect(await groupNames(page)).toEqual([['Basil'], ['Apple', 'Zulu'], ['Mushroom'], ['Unknown']]);
    await expect(page.locator('#extraToppingsGroups .group-title')).toHaveCount(0);
    await expect(page.locator('#extraToppingsGroups input')).toHaveCount(5);
    await expect(page.locator('#defaultToppings')).toContainText('Cheese');
    expect(await page.locator('.topping-price-group').nth(1).evaluate(el => getComputedStyle(el).marginTop)).toBe('8px');
    await expect(page.locator('.topping-price-group').last().locator('.opt-price')).toHaveText('');
    await page.getByLabel('Zulu', {exact: true}).check();
    await expect(page.locator('#stickyTotal')).toHaveText('R105.00');
    await page.locator('#size_23').check();
    await expect(page.locator('#stickyTotal')).toHaveText('R87.00');
    expect(await groupNames(page)).toEqual([['Basil'], ['Apple'], ['Mushroom', 'Zulu'], ['Unknown']]);
    await expect(page.getByLabel('Zulu', {exact: true})).toBeChecked();
    await page.locator('#qtyPlus').click();
    await expect(page.locator('#stickyTotal')).toHaveText('R174.00');
    await expect(page.locator('#stickyQty')).toHaveText('2 items');
    await expect(page.locator('#cardQuote')).toBeHidden();
    await expect(page.locator('#qBase, #qBaseOption, #qToppings, #qSubtotal')).toHaveCount(0);
    await expect(page.locator('#btnAddToCart')).toContainText('Total R174.00');
});

test('read-only pizza menus retain just one Total and cannot add to cart', async ({page}) => {
    await mockCatalog(page);
    await page.goto('/menu/uitzicht');
    await page.locator('#listBody .row').filter({hasText: 'Test Pizza'}).click();
    await expect(page.locator('#cardQuote')).toBeVisible();
    await expect(page.locator('#cardQuote h3')).toHaveText('Total');
    await expect(page.locator('#qTotal')).toHaveText('R100.00');
    await expect(page.locator('#btnAddToCart')).toBeHidden();
});

for (const width of [1280, 390, 320]) {
    test(`entire add bar works once per activation at ${width}px`, async ({page}) => {
        await page.setViewportSize({width, height: 900});
        await startOrder(page);
        for (const activation of ['left', 'centre', 'right', 'padding', 'Enter', 'Space']) {
            await openPizza(page);
            const button = page.locator('#btnAddToCart');
            const box = await button.boundingBox();
            const inner = await button.locator('#stickyQty, #addToCartLabel, .sticky-right').evaluateAll(elements =>
                elements.map(el => {const r = el.getBoundingClientRect(); return {left: r.left, right: r.right};}));
            expect(inner[0].right).toBeLessThanOrEqual(inner[1].left + 1);
            expect(inner[1].right).toBeLessThanOrEqual(inner[2].left + 1);
            expect(box.x).toBeGreaterThanOrEqual(0);
            expect(box.x + box.width).toBeLessThanOrEqual(width);
            if (activation === 'left') await page.screenshot({path: test.info().outputPath(`customize-${width}.png`), fullPage: true});
            const dialogPromise = page.waitForEvent('dialog').then(async dialog => {
                const message = dialog.message();
                await dialog.accept();
                expect(message).toBe('Added to cart!');
            });
            if (activation === 'Enter' || activation === 'Space') {
                await button.focus();
                await page.keyboard.press(activation);
            } else {
                const x = activation === 'left' ? 2 : activation === 'right' ? box.width - 2 : box.width / 2;
                await button.click({position: {x, y: activation === 'padding' ? 2 : box.height / 2}});
            }
            await dialogPromise;
            await expect(page.locator('#viewList')).toBeVisible();
        }
        await page.locator('#btnViewCart').click();
        await expect(page.locator('.sum-item')).toHaveCount(6);
        await expect(page.locator('.sum-total')).toHaveText('TotalR600.00');
        await expect(page.locator('.sum-pricing')).toHaveCount(0);
        await page.screenshot({path: test.info().outputPath(`cart-${width}.png`), fullPage: true});
    });
}

test('burger and menu customization keep totals, required choices, and cart contents', async ({page}) => {
    await startOrder(page);
    await page.locator('#listBody .row').filter({hasText: 'Test Burger'}).click();
    await expect(page.locator('#stickyTotal')).toHaveText('R80.00');
    await expect(page.locator('#cardQuote')).toBeHidden();
    await page.getByLabel('Beef', {exact: true}).check();
    await page.getByLabel('Bacon', {exact: true}).check();
    await expect(page.locator('#stickyTotal')).toHaveText('R90.00');
    let dialog = page.waitForEvent('dialog').then(dialog => dialog.accept());
    await page.locator('#btnAddToCart').click();
    await dialog;
    await page.locator('#listBody .row').filter({hasText: 'Test Pasta'}).click();
    await page.getByLabel('Tomato sauce', {exact: true}).uncheck();
    dialog = page.waitForEvent('dialog').then(async dialog => {
        const message = dialog.message();
        await dialog.accept();
        expect(message).toContain('select at least 1');
    });
    await page.locator('#btnAddToCart').click();
    await dialog;
    await expect(page.locator('#viewCustomize')).toBeVisible();
    await page.getByLabel('Cream sauce', {exact: true}).check();
    await page.locator('#qtyPlus').click();
    await expect(page.locator('#stickyTotal')).toHaveText('R140.00');
    dialog = page.waitForEvent('dialog').then(dialog => dialog.accept());
    await page.locator('#btnAddToCart').click();
    await dialog;
    await page.locator('#btnViewCart').click();
    await expect(page.locator('.sum-total')).toHaveText('TotalR230.00');
    await expect(page.locator('.sum-item').first()).toContainText('Bacon');
    await expect(page.locator('.sum-item').last()).toContainText('Cream sauce');
    await expect(page.locator('.sum-pricing')).toHaveCount(0);
});

test('quote errors remain visible without the removed price panel', async ({page}) => {
    const control = await startOrder(page);
    control.quoteFails = true;
    await page.locator('#listBody .row').filter({hasText: 'Test Pizza'}).click();
    await expect(page.locator('#quoteError')).toBeVisible();
    await expect(page.locator('#quoteError')).toContainText('Quote unavailable');
    const dialog = page.waitForEvent('dialog').then(async dialog => {
        const message = dialog.message();
        await dialog.accept();
        expect(message).toContain('Price not available yet');
    });
    await page.locator('#btnAddToCart').click();
    await dialog;
    await expect(page.locator('#viewCustomize')).toBeVisible();
});

test('loading another pizza clears the previous displayed total until its quote is ready', async ({page}) => {
    const control = await startOrder(page);
    await openPizza(page);
    await page.locator('#btnCustomizeBack').click();
    let release;
    control.detailGate = new Promise(resolve => { release = resolve; });
    try {
        await page.locator('#listBody .row').filter({hasText: 'Test Pizza'}).click();
        await expect(page.locator('#customizePizzaName')).toHaveText('Loading…');
        await expect(page.locator('#stickyTotal')).toHaveText('R0.00');
    } finally {
        release();
    }
    await expect(page.locator('#stickyTotal')).toHaveText('R100.00');
});

test('special component customization and quotes retain only their final total', async ({page}) => {
    const control = await startOrder(page);
    await page.locator('#listBody .row').filter({hasText: 'Test Special'}).click();
    await expect(page.locator('#specialAdd')).toBeDisabled();
    await page.getByLabel('Pizza 1', {exact: true}).selectOption('901');
    await page.locator('#specialSlots button').click();
    await expect(page.locator('#btnAddToCart')).toBeVisible();
    await expect(page.locator('#stickyTotal')).toHaveText('R100.00');
    await page.locator('#btnAddToCart').click();
    await expect(page.locator('#specialOverlay')).toHaveClass(/show/);
    await expect(page.locator('#specialTotal')).toHaveText('R150.00');
    await expect(page.locator('#specialQuoteStatus')).toBeHidden();
    control.specialFails = true;
    await page.locator('#specialQtyPlus').click();
    await expect(page.locator('#specialQuoteStatus')).toContainText('Special unavailable');
    await expect(page.locator('#specialQuoteStatus')).toBeVisible();
    await expect(page.locator('#specialAdd')).toBeDisabled();
    control.specialFails = false;
    await page.locator('#specialQtyMinus').click();
    await expect(page.locator('#specialAdd')).toBeEnabled();
    const dialog = page.waitForEvent('dialog').then(dialog => dialog.accept());
    await page.locator('#specialAdd').click();
    await dialog;
    await page.locator('#btnViewCart').click();
    await expect(page.locator('.sum-total')).toHaveText('TotalR150.00');
    await expect(page.locator('.sum-pricing')).toHaveCount(0);
});

test('checkout removes explanatory copy without breaking choices or address validation', async ({page}) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await startOrder(page);
    await openPizza(page);
    const dialog = page.waitForEvent('dialog').then(dialog => dialog.accept());
    await page.locator('#btnAddToCart').click();
    await dialog;
    await page.locator('#btnViewCart').click();
    await page.locator('#btnConfirmOrder').click();
    await expect(page).toHaveURL(/\/checkout$/);
    await expect(page.getByRole('button', {name: 'Delivery', exact: true})).toBeVisible();
    await expect(page.getByRole('button', {name: 'Collection', exact: true})).toBeVisible();
    await expect(page.locator('#branchName, #orderTypeHint, #gateAccessHint, .terms')).toHaveCount(0);
    for (const text of ['Check your details and order before placing it.', 'Your selected branch is',
        'Choose collection or delivery before placing your order.', 'Deliver to my saved address',
        'Collect from the selected branch', 'Loaded from your Pete’s Pizza account.',
        'Orders are linked to this signed-in account.', 'Payment is completed when you collect',
        'The final total is validated by Pete’s Pizza', 'For estates or complexes with controlled access.']) {
        await expect(page.locator('body')).not.toContainText(text);
    }
    await page.locator('#deliveryButton').click();
    await expect(page.locator('#deliveryFields')).toBeVisible();
    await page.locator('#gateAccessCode').fill('Invalid!');
    expect(await page.locator('#gateAccessCode').evaluate(el => el.validity.patternMismatch)).toBe(true);
    await page.locator('#pickupButton').click();
    await expect(page.locator('#deliveryFields')).toBeHidden();
    await expect(page.locator('#placeOrderButton')).toBeEnabled();
    const brokenReferences = await page.locator('[aria-describedby]').evaluateAll(elements =>
        elements.flatMap(el => el.getAttribute('aria-describedby').split(/\s+/)).filter(id => !document.getElementById(id)));
    expect(brokenReferences).toEqual([]);
    expect(errors).toEqual([]);
    for (const width of [1280, 390]) {
        await page.setViewportSize({width, height: 900});
        await page.screenshot({path: test.info().outputPath(`checkout-${width}.png`), fullPage: true});
    }
});
