const path = require('node:path');
const fs = require('node:fs');
const {createRequire} = require('node:module');
const root = path.resolve(__dirname, '../../..');
const {test, expect} = createRequire(path.join(root, 'SupportConfigFiles/package.json'))('@playwright/test');
test.use({actionTimeout: 10000});
const captureBaseline = process.env.COLOUR_CAPTURE_BASELINE === '1';
const evidence = process.env.COLOUR_AUDIT_OUTPUT || path.join(root, 'target/colour-audit');
const approved = new Set(['37,99,235', '30,64,175', '59,130,246', '147,197,253', '219,234,254']);

function blueColours(value) {
    const colours = [...value.matchAll(/rgba?\(\s*([\d.]+)[, ]+([\d.]+)[, ]+([\d.]+)(?:[, /]+([\d.]+))?\s*\)/g)]
        .filter(m => m[4] !== '0').map(m => m.slice(1, 4).map(Number));
    for (const m of value.replace(/%23/gi, '#').matchAll(/#([\da-f]{6})\b/gi))
        colours.push([0, 2, 4].map(i => parseInt(m[1].slice(i, i + 2), 16)));
    return colours.filter(([r, g, b]) => b > r && b >= g && b - Math.min(r, g) >= 40 && b > 0);
}

// Keep volatile server fixture timestamps stable without changing application data.
async function stableDates(context) {
    await context.route(/\/api\/admin\/(orders|dispatch)(?:\?|$)/, async route => {
        const response = await route.fetch();
        const json = await response.json();
        const normalise = value => {
            if (Array.isArray(value)) return value.map(normalise);
            if (value && typeof value === 'object') return Object.fromEntries(
                Object.entries(value).map(([key, item]) => [key, normalise(item)]));
            return typeof value === 'string' && /^\d{4}-\d\d-\d\dT/.test(value)
                ? '2026-10-22T10:00:00+02:00' : value;
        };
        await route.fulfill({response, json: normalise(json)});
    });
}

async function login(page, username) {
    await page.goto('/login');
    await page.locator('#username').fill(username);
    await page.locator('#password').fill('Browser-test-123');
    await page.getByRole('button', {name: 'Login', exact: true}).click();
    await expect(page).not.toHaveURL(/\/login(?:\?|$)/);
}

async function record(page, name, width, options = {}) {
    await page.evaluate(() => document.fonts.ready);
    await page.evaluate(() => new Promise(requestAnimationFrame));
    await page.evaluate(() => Promise.all(document.getAnimations()
        .filter(animation => animation.effect?.getComputedTiming().iterations !== Infinity)
        .map(animation => animation.finished.catch(() => {}))));
    const data = await page.evaluate(() => {
        const colours = ['color', 'backgroundColor', 'borderTopColor', 'borderRightColor', 'borderBottomColor',
            'borderLeftColor', 'outlineColor', 'boxShadow', 'textDecorationColor', 'backgroundImage', 'fill', 'stroke', 'accentColor'];
        const dimensions = ['fontFamily', 'fontSize', 'fontWeight', 'lineHeight', 'padding', 'margin', 'borderWidth',
            'borderRadius', 'display', 'position', 'gap', 'transitionDuration', 'animationName'];
        return Array.from(document.body.querySelectorAll('*')).filter(el => {
            const rect = el.getBoundingClientRect();
            return !['SCRIPT', 'STYLE', 'IFRAME', 'LINK'].includes(el.tagName) && rect.width && rect.height
                && getComputedStyle(el).visibility !== 'hidden';
        }).flatMap((el, index) => {
            const style = getComputedStyle(el); const rect = el.getBoundingClientRect();
            const result = [{node: `${index}:${el.tagName}#${el.id}.${typeof el.className === 'string' ? el.className : ''}`,
                layout: Object.fromEntries(dimensions.map(key => [key, style[key]])),
                width: rect.width, height: rect.height,
                colours: Object.fromEntries(colours.map(key => [key, style[key]]))}];
            for (const pseudo of ['::before', '::after']) {
                const s = getComputedStyle(el, pseudo);
                if (s.content !== 'none' && s.content !== 'normal') result.push({node: result[0].node + pseudo,
                    colours: Object.fromEntries(colours.map(key => [key, s[key]]))});
            }
            return result;
        });
    });
    const violations = [];
    for (const item of data) for (const [property, value] of Object.entries(item.colours))
        for (const colour of blueColours(value)) if (!approved.has(colour.join(',')))
            violations.push({node: item.node, property, value});
    const phase = captureBaseline ? 'before' : 'after';
    fs.mkdirSync(path.join(evidence, phase), {recursive: true});
    const key = `${name}-${width}`;
    fs.writeFileSync(path.join(evidence, phase, `${key}.json`), JSON.stringify({url: page.url(), data, violations}, null, 2));
    const before = path.join(evidence, 'before', `${key}.json`);
    if (!captureBaseline) {
        expect(violations, `Unapproved blues in ${key}`).toEqual([]);
        if (fs.existsSync(before)) {
            // A continuously rotating spinner has a changing transformed bounding box.
            // Its font, borders and animation stay checked; compare all other rectangles exactly.
            const layout = rows => rows.filter(row => row.layout).map(({node, layout, width, height}) =>
                ({node, layout, width: node.includes('spinner-border') ? null : width,
                    height: node.includes('spinner-border') ? null : height}));
            expect(layout(data), `Non-colour layout changed in ${key}`).toEqual(layout(JSON.parse(fs.readFileSync(before)).data));
        }
    }
    if (options.header) {
        const header = page.locator('body > header');
        await expect(header).toHaveCSS('background-image', 'linear-gradient(rgb(31, 41, 55), rgb(31, 41, 55))');
        await expect(header).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)');
        await expect(header).toHaveCSS('opacity', '1');
        const image = await header.screenshot({animations: 'disabled'});
        const original = path.join(evidence, 'before', `${key}-header.png`);
        fs.writeFileSync(path.join(evidence, phase, `${key}-header.png`), image);
        if (!captureBaseline && fs.existsSync(original))
            expect(image.equals(fs.readFileSync(original)), `Protected homepage header pixels changed in ${key}`).toBe(true);
    }
    if (options.screenshot) await page.screenshot({path: path.join(evidence, phase, `${key}.png`)});
}

const gallery = `
<section id="colourComponents" aria-label="Colour states">
 <button class="btn btn-primary">Primary action</button><button class="btn btn-secondary">Secondary action</button>
 <button class="btn btn-info">Information</button><button class="btn btn-outline-info">Info outline</button>
 <button class="btn btn-secondary" disabled>Disabled</button><a class="btn btn-link" href="#colourComponents">Link action</a>
 <div class="alert alert-info">Information alert</div><div class="alert alert-primary">Primary alert</div>
 <nav class="nav nav-pills"><a class="nav-link active" href="#colourComponents">Active navigation</a><a class="nav-link" href="#colourComponents">Navigation</a></nav>
 <ul class="pagination"><li class="page-item active"><a class="page-link" href="#colourComponents">1</a></li><li class="page-item"><a class="page-link" href="#colourComponents">2</a></li></ul>
 <div class="progress"><div class="progress-bar" style="width:50%">Loading</div></div>
 <div class="accordion"><button class="accordion-button" type="button">Expanded choice</button><button class="accordion-button collapsed" type="button">Collapsed choice</button></div>
 <label>Select <select class="form-select"><option>Choice</option></select></label>
 <label>Input <input class="form-control" placeholder="Example"></label>
 <div class="form-check form-switch"><input type="checkbox" class="form-check-input" aria-label="Switch"><label>Switch</label></div>
 <label>Range <input class="form-range" type="range" aria-label="Range"></label>
 <div class="dropdown-menu show"><a class="dropdown-item active" href="#colourComponents">Selected item</a><a class="dropdown-item" href="#colourComponents">Other item</a></div>
 <span class="spinner-border text-info" role="status"></span><span class="badge text-bg-info">Information badge</span>
</section>`;

for (const width of [1280, 390]) test(`web colour and layout audit at ${width}px`, async ({browser}) => {
    test.setTimeout(180000);
    const context = await browser.newContext({viewport: {width, height: 844}});
    await context.route(/google\.com\/maps|maps\.googleapis\.com/, route => route.abort());
    const page = await context.newPage();
    for (const [name, url] of [['home-guest', '/'], ['login', '/login'], ['admin-login', '/admin/login'],
        ['register', '/register'], ['forgot', '/forgot-password'], ['reset', '/reset-password'],
        ['kenridge', '/menu/kenridge'], ['uitzicht', '/menu/uitzicht']]) {
        await page.goto(url); await page.waitForLoadState('networkidle');
        await record(page, name, width, {header: name === 'home-guest', screenshot: true});
    }
    await login(page, 'browser@example.com');
    await record(page, 'home-customer', width, {header: true});
    await page.locator('.view-profile-btn').hover();
    await record(page, 'home-profile-hover', width, {header: true});
    await page.locator('.view-profile-btn').focus();
    await record(page, 'home-profile-focus', width, {header: true});
    for (const [name, url] of [['profile-history', '/profile/edit'], ['confirmation-tracking', '/orders/1/confirmation'],
        ['checkout-empty', '/checkout'], ['error', '/not-a-real-page']]) {
        await page.goto(url); await page.waitForLoadState('networkidle');
        await record(page, name, width, {screenshot: true});
    }
    await page.goto('/order');
    const branch = page.locator('#branchKenridge'); if (await branch.isVisible()) await branch.click();
    await page.locator('#listBody .row').filter({hasText: /Click to customize|Click to add/}).first().click();
    await expect(page.locator('#btnAddToCart')).toBeVisible();
    await expect(page.locator('#stickyTotal')).not.toHaveText('R0.00');
    await record(page, 'customisation', width, {screenshot: true});
    await page.locator('#btnAddToCart').click(); await page.locator('#btnViewCart').click();
    await expect(page.locator('#btnConfirmOrder')).toBeVisible();
    await record(page, 'cart', width, {screenshot: true});
    await page.locator('#btnConfirmOrder').click(); await expect(page).toHaveURL(/\/checkout$/);
    await page.locator('#deliveryButton').click();
    await record(page, 'checkout-delivery', width, {screenshot: true});
    await page.locator('#pickupButton').click();
    await record(page, 'checkout-collection', width);
    await page.locator('#fullName').focus();
    await record(page, 'checkout-focus', width);
    await context.close();

    const manager = await browser.newContext({viewport: {width, height: 844}});
    await stableDates(manager);
    const admin = await manager.newPage(); await login(admin, 'browser-admin');
    for (const tab of ['overview', 'orders', 'dispatch', 'users', 'items', 'specials', 'pricing', 'categories', 'ingredients', 'modifiers', 'accounts']) {
        await admin.goto('/admin#' + tab); await admin.waitForLoadState('networkidle');
        if (tab === 'dispatch') await expect(admin.locator('#dispatchQueue .dispatch-row')).toHaveCount(2);
        await record(admin, 'admin-' + tab, width, {screenshot: true});
    }
    await admin.goto('/admin#items');
    await admin.locator('[data-bs-target="#pizzaModal"]').first().click();
    await expect(admin.locator('#pizzaModal')).toBeVisible();
    await record(admin, 'admin-product-modal', width, {screenshot: true});
    await admin.locator('#pizzaModal [data-bs-dismiss="modal"]').first().click();
    await admin.goto('/input-orders'); await admin.waitForLoadState('networkidle');
    await record(admin, 'legacy-orders', width, {screenshot: true});
    await admin.goto('/admin');
    await admin.evaluate(html => {const holder = document.createElement('div'); holder.innerHTML = html; document.querySelector('main').append(holder);}, gallery);
    await record(admin, 'components', width, {screenshot: true});
    for (const selector of ['#colourComponents .btn-secondary:not(:disabled)', '#colourComponents .form-select',
        '#colourComponents .accordion-button', '#colourComponents .form-check-input', '#colourComponents .page-link']) {
        await admin.locator(selector).first().focus();
        await record(admin, 'focus-' + selector.replace(/[^a-z0-9]/gi, ''), width);
    }
    await admin.locator('#colourComponents .btn-secondary:not(:disabled)').hover();
    await record(admin, 'components-hover', width);
    await admin.locator('#colourComponents .form-check-input').check();
    await record(admin, 'components-checked', width);
    await admin.locator('#colourComponents .form-control').evaluate(el => el.classList.add('is-invalid'));
    await record(admin, 'components-validation', width, {screenshot: true});
    await admin.locator('#colourComponents .form-check-input').evaluate(el => {el.checked = false; el.indeterminate = true;});
    await record(admin, 'components-indeterminate', width);
    await admin.locator('#colourComponents .btn-info').evaluate(el => el.classList.add('active'));
    await record(admin, 'components-active', width);
    // Dedicated disposable accounts avoid changing the dispatch fixtures used by other suites.
    const email = `colour-driver-${width}@example.com`;
    const registered = await manager.request.post('/api/auth/register', {data: {
        firstName: 'ZZColour', lastName: 'Driver', email, password: 'Browser-test-123', phone: `079000${width.toString().padStart(4, '0')}`,
        preferredStore: 'Kenridge Branch', address: {googlePlaceId: 'places/colour-fixture',
            formattedAddress: '12 Main Street, Kenridge, Cape Town, 7550, South Africa',
            latitude: -33.861, longitude: 18.65, houseNumber: '12', street: 'Main Street', area: 'Kenridge',
            city: 'Cape Town', postalCode: '7550', country: 'South Africa', province: 'Western Cape'}
    }});
    expect(registered.status()).toBe(201);
    const csrf = await admin.locator('meta[name="_csrf"]').getAttribute('content');
    const promoted = await manager.request.post('/api/admin/dispatch/drivers', {
        headers: {'X-CSRF-TOKEN': csrf}, data: {email, branchId: 1}
    });
    expect(promoted.status()).toBe(200);
    const driverContext = await browser.newContext({viewport: {width, height: 844}});
    const driver = await driverContext.newPage(); let onShift = false;
    await driver.route('**/api/driver', async route => {
        const response = await route.fetch(); const data = await response.json();
        data.shift = onShift ? {id: 9999, state: 'ACTIVE', businessDate: '2026-10-22',
            clockedInAt: '2026-10-22T08:00:00+02:00', hourlyRate: 0, perDeliveryRate: 0} : null;
        data.deliveries = onShift ? ['ASSIGNED', 'PICKED_UP', 'DELIVERED'].map((state, index) => ({
            assignmentId: 9900 + index, state, total: 100, order: {id: 9900 + index, status: 'Preparing',
                customerName: 'Colour Customer', phone: '0800000000', formattedAddress: '12 Test Road',
                menuItems: [{name: 'Test pizza', quantity: 1}]}
        })) : [];
        await route.fulfill({response, json: data});
    });
    await login(driver, email); await expect(driver).toHaveURL(/\/driver$/);
    await expect(driver.locator('#driverShiftState')).toHaveText('Off shift');
    await record(driver, 'driver-empty', width, {screenshot: true});
    onShift = true; await driver.reload();
    await expect(driver.locator('#driverOrders')).toContainText('Colour Customer');
    await record(driver, 'driver-assignments', width, {screenshot: true});
    await driverContext.close(); await manager.close();
});
