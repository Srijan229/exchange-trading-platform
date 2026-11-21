import React, { useCallback, useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Activity, ArrowDownLeft, ArrowUpRight, Beaker, Check, ChevronDown, CircleDollarSign, Clock3, FlaskConical, Layers3, LockKeyhole, Play, RefreshCw, ShieldCheck, Sparkles, Wallet, X } from 'lucide-react';
import './style.css';

type Market = { symbol: string; mark_cents: number };
type Account = { id: string; cash_cents: number; equity_cents: number; position_margin_cents: number; maintenance_margin_cents: number; reserved_cents: number };
type Order = { sequence: number; client_id: string; account_id: string; symbol: string; side: 'BUY' | 'SELL'; price_cents: number; quantity: number; remaining: number; status: string; reduce_only: boolean };
type Position = { account_id: string; symbol: string; quantity: number; entry_cents: number; mark_cents: number; unrealized_pnl_cents: number };
type Fill = { id: number; buy_order: number; sell_order: number; quantity: number; price_cents: number; created_at: string };
type TimelineEvent = { id: number; description: string; created_at: string };
type Snapshot = { markets: Market[]; accounts: Account[]; orders: Order[]; positions: Position[]; fills: Fill[]; events: TimelineEvent[] };
type Scenario = { type: 'liquidation' | 'collateral'; step: number };
const empty: Snapshot = { markets: [], accounts: [], orders: [], positions: [], fills: [], events: [] };
const money = (cents: number | undefined) => cents == null ? '—' : new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(cents / 100);
const number = (value: number | undefined) => value == null ? '—' : new Intl.NumberFormat('en-US').format(value);
const time = (value: string) => new Date(value).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
function cents(value: string) {
  if (!/^\d+(\.\d{1,2})?$/.test(value)) throw new Error('Enter a positive price with at most two decimal places.');
  const [whole, decimal = ''] = value.split('.');
  const result = Number(whole) * 100 + Number(decimal.padEnd(2, '0'));
  if (!Number.isSafeInteger(result) || result <= 0) throw new Error('Enter a valid positive price.');
  return result;
}
async function request<T>(base: string, path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(base + path, { ...init, headers: { 'Content-Type': 'application/json', ...init?.headers } });
  if (!response.ok) {
    let detail = `${response.status} ${response.statusText}`;
    try { const body = await response.json(); detail = body.message || body.error || detail; } catch { /* response was not JSON */ }
    throw new Error(detail);
  }
  return response.json() as Promise<T>;
}
const post = <T,>(base: string, path: string, body?: unknown) => request<T>(base, path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) });
const put = <T,>(base: string, path: string, body: unknown) => request<T>(base, path, { method: 'PUT', body: JSON.stringify(body) });
function id(prefix: string) { return `${prefix}-${crypto.randomUUID()}`; }

function App() {
  const [mode, setMode] = useState<'exchange' | 'demo'>('exchange');
  const base = mode === 'demo' ? '/demo-api' : '/api';
  const [data, setData] = useState<Snapshot>(empty);
  const [market, setMarket] = useState('BTC-PERP');
  const [accountId, setAccountId] = useState('alice');
  const [side, setSide] = useState<'BUY' | 'SELL'>('BUY');
  const [price, setPrice] = useState('50.00');
  const [quantity, setQuantity] = useState('1');
  const [markInput, setMarkInput] = useState('');
  const [connection, setConnection] = useState<'connecting' | 'live' | 'offline'>('connecting');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState('');
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [scenario, setScenario] = useState<Scenario | null>(() => { try { return JSON.parse(sessionStorage.getItem('exchange-demo') || 'null'); } catch { return null; } });
  const generation = useRef(0);
  const pendingOrder = useRef<{ payload: string; clientId: string } | null>(null);

  const refresh = useCallback(async () => {
    const current = generation.current;
    try {
      const [markets, accounts, orders, positions, fills, events] = await Promise.all([
        request<Market[]>(base, '/markets'), request<Account[]>(base, '/accounts'), request<Order[]>(base, '/orders'),
        request<Position[]>(base, '/positions'), request<Fill[]>(base, '/fills'), request<TimelineEvent[]>(base, '/events'),
      ]);
      if (current === generation.current) { setData({ markets, accounts, orders, positions, fills, events }); setLoading(false); }
    } catch { if (current === generation.current) { setConnection('offline'); setLoading(false); } }
  }, [base]);

  useEffect(() => {
    generation.current++;
    setData(empty); setLoading(true); setConnection('connecting');
    void refresh();
    const stream = new EventSource(`${base}/stream`);
    stream.onopen = () => { setConnection('live'); void refresh(); };
    stream.onerror = () => setConnection('offline');
    const reload = () => void refresh();
    stream.addEventListener('ready', reload); stream.addEventListener('change', reload); stream.addEventListener('refresh', reload);
    return () => { generation.current++; stream.close(); };
  }, [base, refresh]);
  useEffect(() => { sessionStorage.setItem('exchange-demo', JSON.stringify(scenario)); }, [scenario]);

  const selectedMarket = data.markets.find(item => item.symbol === market);
  const account = data.accounts.find(item => item.id === accountId);
  const available = account ? Number(account.equity_cents) - Number(account.position_margin_cents) - Number(account.reserved_cents) : undefined;
  const selectedPositions = data.positions.filter(item => item.account_id === accountId);
  const open = data.orders.filter(item => item.symbol === market && item.status === 'OPEN');
  const asks = open.filter(item => item.side === 'SELL').sort((a, b) => a.price_cents - b.price_cents || a.sequence - b.sequence).slice(0, 6);
  const bids = open.filter(item => item.side === 'BUY').sort((a, b) => b.price_cents - a.price_cents || a.sequence - b.sequence).slice(0, 6);
  const trades = data.fills.filter(fill => data.orders.some(order => order.sequence === fill.buy_order && order.symbol === market)).slice(0, 6);
  const ownOrders = data.orders.filter(order => order.account_id === accountId && order.status === 'OPEN').slice(0, 8);

  async function action(label: string, run: () => Promise<void>) {
    if (busy) return;
    setBusy(label); setError(''); setMessage('');
    try { await run(); await refresh(); } catch (e) { setError(e instanceof Error ? e.message : 'Something went wrong.'); }
    finally { setBusy(''); }
  }
  async function submitOrder(e: React.FormEvent) {
    e.preventDefault();
    await action('order', async () => {
      const qty = Number(quantity);
      if (!Number.isSafeInteger(qty) || qty <= 0) throw new Error('Quantity must be a positive whole number.');
      const priceCents = cents(price);
      const payload = JSON.stringify({ base, accountId, symbol: market, side, priceCents, quantity: qty });
      if (pendingOrder.current?.payload !== payload) pendingOrder.current = { payload, clientId: id('dashboard') };
      const result = await post<Order>(base, '/orders', { clientId: pendingOrder.current.clientId, accountId, symbol: market, side, priceCents, quantity: qty });
      pendingOrder.current = null;
      setMessage(`Order ${result.status.toLowerCase()}: ${side.toLowerCase()} ${qty} ${market}.`);
    });
  }
  async function changeMark(e: React.FormEvent) {
    e.preventDefault();
    await action('mark', async () => { await put(base, `/markets/${market}/mark`, { priceCents: cents(markInput) }); setMessage(`${market} mark updated.`); setMarkInput(''); });
  }
  async function demoOrder(clientId: string, account: string, symbol: string, orderSide: string, priceCents: number, qty: number) {
    return post<Order>('/demo-api', '/orders', { clientId, accountId: account, symbol, side: orderSide, priceCents, quantity: qty });
  }
  async function loadDemo(type: Scenario['type']) {
    await action('demo', async () => {
      await post('/demo-api', '/demo/reset');
      if (type === 'liquidation') {
        await demoOrder('demo-initial-sell', 'maker', 'BTC-PERP', 'SELL', 5000, 10);
        await demoOrder('demo-initial-buy', 'alice', 'BTC-PERP', 'BUY', 5000, 10);
      }
      setMarket('BTC-PERP'); setAccountId('alice'); setScenario({ type, step: 0 }); setMode('demo');
      setMessage(type === 'liquidation' ? 'Liquidation scenario loaded in the scenario environment.' : 'Shared collateral scenario loaded in the scenario environment.');
    });
  }
  async function nextDemo() {
    if (!scenario) return;
    await action('demo', async () => {
      if (scenario.type === 'liquidation') {
        if (scenario.step === 0) { await put('/demo-api', '/markets/BTC-PERP/mark', { priceCents: 4500 }); setMessage('Mark dropped to $45. Alice is below maintenance margin.'); }
        else if (scenario.step === 1) { await demoOrder('demo-buyer-one', 'buyer1', 'BTC-PERP', 'BUY', 4500, 5); setMessage('First buyer filled five contracts. You can restart the scenario backend now.'); }
        else if (scenario.step === 2) { await demoOrder('demo-buyer-two', 'buyer2', 'BTC-PERP', 'BUY', 4500, 5); setMessage('Second buyer filled five contracts. Alice is flat.'); }
      } else {
        if (scenario.step === 0) {
          const results = await Promise.allSettled([
            demoOrder('demo-race-btc', 'alice', 'BTC-PERP', 'BUY', 3500, 10),
            demoOrder('demo-race-eth', 'alice', 'ETH-PERP', 'BUY', 3500, 10),
          ]);
          const rejected = results.find((result): result is PromiseRejectedResult => result.status === 'rejected');
          if (results.filter(result => result.status === 'fulfilled').length !== 1 || !rejected || !(rejected.reason instanceof Error) || !rejected.reason.message.toLowerCase().includes('insufficient available collateral')) throw new Error('The two-order check did not complete as expected. Reload the scenario to try again.');
          setMessage('One order was accepted and the other rejected. Alice has $70 reserved.');
        } else if (scenario.step === 1) {
          const orders = await request<Order[]>('/demo-api', '/orders');
          const accepted = orders.find(order => order.client_id.startsWith('demo-race-') && order.status === 'OPEN');
          if (!accepted) throw new Error('No open scenario order found. Reload the scenario.');
          await request('/demo-api', `/orders/${accepted.client_id}`, { method: 'DELETE' });
          const other = accepted.symbol === 'BTC-PERP' ? 'ETH-PERP' : 'BTC-PERP';
          await demoOrder('demo-fresh-other', 'alice', other, 'BUY', 3500, 10);
          setMessage(`Reservation released; a fresh ${other} order was accepted.`);
        }
      }
      setScenario({ ...scenario, step: scenario.step + 1 });
    });
  }
  const stepLabels = scenario?.type === 'liquidation' ? ['Drop mark to $45', 'Add first buyer · 5 contracts', 'Add second buyer · 5 contracts'] : ['Submit simultaneous orders', 'Cancel & try other market'];
  const stepDescriptions = scenario?.type === 'liquidation'
    ? ['Alice holds 10 BTC contracts at $50. Drop the mark to begin liquidation.', 'Alice’s equity is $50 against $54 maintenance. Fill half the liquidation.', 'Five contracts remain. Restart the scenario backend if needed, then finish the fill.', 'Alice is flat. Review the two liquidation fills and account balance.']
    : ['Alice has $100. Two $350 orders each need $70 in collateral.', 'One order won. Cancel it and place a fresh order in the other market.', 'The new order is open; the reservation has moved to the other market.'];

  return <div className="app-shell">
    <aside className="sidebar">
      <div className="brand"><div className="brand-icon"><Layers3 size={21}/></div><div><strong>exchange<span>.</span></strong><small>PAPER TRADING</small></div></div>
      <div className="nav-label">WORKSPACE</div><div className="nav-item active"><Activity size={17}/> Exchange <span className="nav-indicator"/></div><a className="nav-item" href="#positions"><Wallet size={17}/> Account</a><a className="nav-item" href="#timeline"><Clock3 size={17}/> Activity</a>
      <div className="sidebar-bottom"><div className="side-note"><ShieldCheck size={18}/><div><strong>Paper trading</strong><p>Orders use virtual funds and practice prices.</p></div></div><div className="side-version">EXCHANGE PLATFORM <span>v0.1</span></div></div>
    </aside>
    <main className="workspace">
      <header className="topbar"><div><span className="breadcrumb">WORKSPACE</span><span className="crumb-separator">/</span><span className="crumb-current">Exchange</span></div><div className="top-right"><div className={`connection ${connection}`}><span/>{connection === 'live' ? 'Live connection' : connection === 'connecting' ? 'Connecting' : 'Reconnecting'}</div><div className="avatar">ES</div></div></header>
      <div className="content">
        <div className="page-heading"><div><div className="eyebrow"><span className="eyebrow-line"/> FUTURES TRADING PLATFORM</div><h1>Trading workspace<span>.</span></h1><p>Monitor markets, manage orders, and review account risk.</p></div><div className="mode-switch" aria-label="Data source"><button className={mode === 'exchange' ? 'selected' : ''} onClick={() => setMode('exchange')}>Exchange</button><button className={mode === 'demo' ? 'selected' : ''} onClick={() => setMode('demo')}><FlaskConical size={14}/> Scenarios</button></div></div>
        {mode === 'demo' && <div className="demo-banner"><Beaker size={16}/><span>You’re viewing the <strong>isolated scenario environment</strong>. Loading a scenario resets scenario data only.</span></div>}
        {(error || message) && <div className={`notice ${error ? 'error' : 'success'}`} role="status">{error ? error : message}<button aria-label="Dismiss message" onClick={() => { setError(''); setMessage(''); }}><X size={16}/></button></div>}
        <div className="market-strip"><div className="market-picker"><div className="coin-icon">{market.startsWith('BTC') ? '₿' : 'Ξ'}</div><div><span className="market-overline">SELECTED MARKET</span><label className="select-wrap"><select value={market} onChange={e => setMarket(e.target.value)} aria-label="Select market"><option>BTC-PERP</option><option>ETH-PERP</option></select><ChevronDown size={16}/></label></div></div><div className="strip-stat"><span>MARK PRICE</span><strong>{money(selectedMarket?.mark_cents)}</strong></div><div className="strip-stat"><span>CONTRACT TYPE</span><strong className="small-stat">Perpetual futures</strong></div><div className="strip-stat"><span>SETTLEMENT</span><strong className="small-stat">USD <span className="tag muted">PAPER</span></strong></div><form className="mark-form" onSubmit={changeMark}><label htmlFor="mark">UPDATE MARK</label><div><span>$</span><input id="mark" inputMode="decimal" value={markInput} onChange={e => setMarkInput(e.target.value)} placeholder="0.00"/><button disabled={!!busy || !markInput} aria-label="Update mark price"><RefreshCw size={15}/></button></div></form></div>
        <div className="main-grid">
          <section className="card book-card"><div className="card-header"><div><div className="section-kicker">MARKET DEPTH</div><h2>Order book</h2></div><span className="tag"><span className="tiny-dot"/> {market}</span></div><div className="table-head"><span>PRICE <i>(USD)</i></span><span>SIZE <i>(CONTRACTS)</i></span><span>ACCOUNT</span></div><div className="book-side">{asks.length ? [...asks].reverse().map(order => <BookRow key={order.sequence} order={order}/>) : <Empty text="No sell orders yet"/>}</div><div className="book-mid"><div><span>MARK PRICE</span><strong>{money(selectedMarket?.mark_cents)}</strong></div><span className="mid-caption">Current mark reference</span></div><div className="book-side">{bids.length ? bids.map(order => <BookRow key={order.sequence} order={order}/>) : <Empty text="No buy orders yet"/>}</div><div className="card-foot"><span>PRICE-TIME PRIORITY</span><span>Live order book</span></div></section>
          <section className="card ticket-card"><div className="card-header"><div><div className="section-kicker">ORDER ENTRY</div><h2>Place an order</h2></div><div className="header-icon"><ArrowUpRight size={17}/></div></div><div className="side-tabs"><button className={side === 'BUY' ? 'buy-active' : ''} onClick={() => setSide('BUY')}>Buy / Long</button><button className={side === 'SELL' ? 'sell-active' : ''} onClick={() => setSide('SELL')}>Sell / Short</button></div><form onSubmit={submitOrder} className="order-form"><div className="input-label"><label htmlFor="account">ACCOUNT</label><span>PAPER ACCOUNT</span></div><div className="input-control select-control"><select id="account" value={accountId} onChange={e => setAccountId(e.target.value)}>{data.accounts.length === 0 && <option value="alice">alice</option>}{data.accounts.map(a => <option key={a.id} value={a.id}>{a.id}</option>)}</select><ChevronDown size={16}/></div><div className="input-label"><label htmlFor="price">LIMIT PRICE</label><span>USD</span></div><div className="input-control"><span>$</span><input id="price" inputMode="decimal" value={price} onChange={e => setPrice(e.target.value)} /></div><div className="input-label"><label htmlFor="quantity">QUANTITY</label><span>CONTRACTS</span></div><div className="input-control"><input id="quantity" type="number" min="1" step="1" value={quantity} onChange={e => setQuantity(e.target.value)} /><span>contracts</span></div><div className="ticket-summary"><div><span>Initial margin</span><strong>20%</strong></div><div><span>Available collateral</span><strong>{money(available)}</strong></div></div><button className={`submit-order ${side === 'SELL' ? 'sell-button' : ''}`} disabled={!!busy || loading}>{busy === 'order' ? 'Placing order…' : `${side === 'BUY' ? 'Buy / Long' : 'Sell / Short'} ${market}`}</button><p className="ticket-hint"><LockKeyhole size={13}/> Orders use virtual funds.</p></form></section>
          <section className="card account-card" id="positions"><div className="card-header"><div><div className="section-kicker">YOUR PORTFOLIO</div><h2>Account overview</h2></div><label className="account-select"><select aria-label="Select account for overview" value={accountId} onChange={e => setAccountId(e.target.value)}>{data.accounts.length === 0 && <option value="alice">alice</option>}{data.accounts.map(a => <option key={a.id} value={a.id}>{a.id}</option>)}</select><ChevronDown size={14}/></label></div><div className="equity"><span>TOTAL EQUITY</span><strong>{money(account?.equity_cents)}</strong><p><span className="equity-dot"/> USD collateral · paper account</p></div><div className="account-stats"><div><span>Cash balance</span><strong>{money(account?.cash_cents)}</strong></div><div><span>Available</span><strong>{money(available)}</strong></div><div><span>Position margin</span><strong>{money(account?.position_margin_cents)}</strong></div><div><span>Order reservations</span><strong>{money(account?.reserved_cents)}</strong></div><div><span>Maintenance margin</span><strong>{money(account?.maintenance_margin_cents)}</strong></div></div><div className="subhead"><h3>Open positions</h3><span>{selectedPositions.length}</span></div>{selectedPositions.length ? selectedPositions.map(position => <div className="position-row" key={position.symbol}><div><strong>{position.symbol}</strong><span>{position.quantity > 0 ? 'Long' : 'Short'} · {Math.abs(position.quantity)} contracts</span></div><div className="position-right"><strong className={position.unrealized_pnl_cents >= 0 ? 'positive' : 'negative'}>{position.unrealized_pnl_cents > 0 ? '+' : ''}{money(position.unrealized_pnl_cents)}</strong><span>Entry {money(position.entry_cents)}</span></div></div>) : <div className="empty-position">No open positions for this account.</div>}</section>
          <section className="card trades-card"><div className="card-header compact"><div><div className="section-kicker">RECENT ACTIVITY</div><h2>Trade history</h2></div><span className="tag muted">{market}</span></div><div className="trade-table-head"><span>TIME</span><span>PRICE</span><span>QUANTITY</span><span>TRADE</span></div>{trades.length ? trades.map(fill => <div className="trade-row" key={fill.id}><span>{time(fill.created_at)}</span><strong>{money(fill.price_cents)}</strong><span>{number(fill.quantity)} contracts</span><span>#{fill.id}</span></div>) : <div className="empty-wide">No trades yet. Matched orders appear here.</div>}</section>
          <section className="card orders-card"><div className="card-header compact"><div><div className="section-kicker">ACCOUNT ACTIVITY</div><h2>Open orders</h2></div><span className="tag muted">{ownOrders.length} OPEN</span></div>{ownOrders.length ? ownOrders.map(order => <div className="own-order" key={order.sequence}><div className={`side-icon ${order.side === 'BUY' ? 'buy' : 'sell'}`}>{order.side === 'BUY' ? <ArrowDownLeft size={16}/> : <ArrowUpRight size={16}/>}</div><div className="own-order-info"><strong>{order.side === 'BUY' ? 'Buy' : 'Sell'} {order.symbol} {order.reduce_only && <span className="reduce-tag">REDUCE ONLY</span>}</strong><span>{order.remaining} of {order.quantity} at {money(order.price_cents)}</span></div>{!order.reduce_only && <button className="cancel" disabled={!!busy} onClick={() => void action('cancel', async () => { await request(base, `/orders/${encodeURIComponent(order.client_id)}`, { method: 'DELETE' }); setMessage('Order cancelled and reservation released.'); })}>Cancel</button>}</div>) : <div className="empty-wide">No open orders for {accountId}.</div>}</section>
        </div>
        <div className="lower-grid"><section className="card demo-card"><div className="card-header compact"><div><div className="section-kicker">RISK & OPERATIONS</div><h2>Scenario testing <span className="lab-pill"><Sparkles size={12}/> ISOLATED</span></h2></div><Beaker className="demo-icon" size={20}/></div><p className="demo-intro">Run controlled scenarios for liquidation recovery and shared collateral. Loading a scenario resets only the separate scenario environment.</p><div className="demo-options"><button className={scenario?.type === 'liquidation' && mode === 'demo' ? 'demo-option chosen' : 'demo-option'} disabled={!!busy} onClick={() => void loadDemo('liquidation')}><span className="option-icon amber"><ShieldCheck size={17}/></span><span><strong>Liquidation recovery</strong><small>Restart after a partial fill</small></span><Play size={15}/></button><button className={scenario?.type === 'collateral' && mode === 'demo' ? 'demo-option chosen' : 'demo-option'} disabled={!!busy} onClick={() => void loadDemo('collateral')}><span className="option-icon violet"><CircleDollarSign size={17}/></span><span><strong>Shared collateral</strong><small>Two markets, one balance</small></span><Play size={15}/></button></div>{scenario && mode === 'demo' && <div className="scenario"><div className="scenario-top"><span>SCENARIO IN PROGRESS</span><span>STEP {Math.min(scenario.step + 1, stepDescriptions.length)} / {stepDescriptions.length}</span></div><p>{stepDescriptions[Math.min(scenario.step, stepDescriptions.length - 1)]}</p>{scenario.type === 'liquidation' && scenario.step === 2 && <div className="restart-note">Optional restart in PowerShell: <code>docker compose kill demo-backend</code> then <code>docker compose up -d demo-backend</code>. Wait for the live connection to return.</div>}{scenario.step < stepLabels.length ? <button className="next-step" disabled={!!busy} onClick={() => void nextDemo()}><Play size={14}/> {busy === 'demo' ? 'Working…' : stepLabels[scenario.step]}</button> : <div className="finished"><Check size={16}/> Scenario complete</div>}</div>}</section>
          <section className="card timeline-card" id="timeline"><div className="card-header compact"><div><div className="section-kicker">EXCHANGE LOG</div><h2>Event timeline</h2></div><span className="tag muted">LATEST</span></div><div className="timeline">{data.events.length ? data.events.slice(0, 6).map(event => <div className="timeline-row" key={event.id}><span className="timeline-marker"/><div><strong>{event.description.replace("Demo reset: isolated demo accounts restored", "Scenario environment reset")}</strong><small>{time(event.created_at)}</small></div></div>) : <div className="empty-wide">Events will appear here as the exchange is used.</div>}</div></section></div>
        <footer><span><span className="footer-icon">◆</span> EXCHANGE PLATFORM</span><span>Paper trading environment. No real funds are used.</span></footer>
      </div>
    </main>
  </div>;
}
function BookRow({ order }: { order: Order }) { return <div className="book-row"><strong className={order.side === 'BUY' ? 'positive' : 'negative'}>{money(order.price_cents)}</strong><span>{number(order.remaining)}</span><span>{order.account_id}{order.reduce_only ? ' · reduce' : ''}</span></div>; }
function Empty({ text }: { text: string }) { return <div className="empty-book">{text}</div>; }

createRoot(document.getElementById('root')!).render(<React.StrictMode><App/></React.StrictMode>);
