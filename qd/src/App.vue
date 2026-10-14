<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import AuthDialog from './components/AuthDialog.vue'
import SessionSidebar from './components/SessionSidebar.vue'
import { useApi } from './composables/useApi.js'
import { logoutSafely, shouldForgetSession } from './lib/failurePolicy.js'
import {
  Activity,
  ArrowUpRight,
  Bot,
  Check,
  ChevronDown,
  CirclePlus,
  CircleHelp,
  ClipboardList,
  Clock3,
  Headphones,
  LoaderCircle,
  Menu,
  PackageSearch,
  PanelRight,
  Plus,
  Search,
  Send,
  ShoppingBag,
  Sparkles,
  Trash2,
  Truck,
  UserRound,
  X,
} from 'lucide-vue-next'

const userId = ref('')
const account = ref(null)
const authMode = ref('login')
const authForm = ref({ username: '', password: '' })
const authLoading = ref(false)
const authReady = ref(false)
const userDialogOpen = ref(false)
const mobileSessionsOpen = ref(false)
const mobileToolsOpen = ref(false)
const sessions = ref([])
const selectedSessionId = ref('')
const messages = ref([])
const loadedSessionId = ref('')
const historyHasMore = ref(false)
const historyLoadingMore = ref(false)
const draft = ref('')
const isSending = ref(false)
const isLoadingHistory = ref(false)
const isCreatingSession = ref(false)
const notice = ref('')
const noticeKind = ref('error')
const activeTool = ref('order')
const workspaceMode = ref('service')
const cart = ref([])
const cartQuantityDrafts = ref({})
const customerOrders = ref([])
const selectedOrder = ref(null)
const orderLookup = ref('')
const commerceProducts = ref([])
const selectedProduct = ref(null)
const productDetailLoading = ref(false)
const commerceKeyword = ref('')
const commerceLoading = ref(false)
const storeProducts = ref([])
const storeOrders = ref([])
const storeLoading = ref(false)
const storeProductPage = ref(0)
const storeOrderPage = ref(0)
const storeProductHasMore = ref(false)
const storeOrderHasMore = ref(false)
const newProduct = ref({ skuId: '', name: '', description: '', price: '', availableQuantity: 0 })
const productEditDrafts = ref({})
const shipmentDrafts = ref({})
const orderNo = ref('')
const productKeyword = ref('')
const orderResult = ref(null)
const logisticsResult = ref(null)
const products = ref([])
const isQuerying = ref(false)
const toolError = ref('')
const messageList = ref(null)
const showScrollToBottom = ref(false)
let noticeTimer
let historyRequestVersion = 0

const currentSession = computed(() => sessions.value.find((item) => item.sessionId === selectedSessionId.value) || null)
const canSend = computed(() => Boolean(draft.value.trim()) && !isSending.value && Boolean(account.value))
const displayUser = computed(() => account.value?.username || '未登录')
const isCustomer = computed(() => account.value?.role === 'CUSTOMER')
const isStoreEmployee = computed(() => account.value?.role === 'STORE_EMPLOYEE')
const workspaceTitle = computed(() => workspaceMode.value === 'store' ? '店铺工作台' : workspaceMode.value === 'commerce' ? '顾客交易' : '客服工作台')

function sessionStorageKey(id = userId.value) {
  return `xinghe:sessions:${encodeURIComponent(id)}`
}

function selectedStorageKey(id = userId.value) {
  return `xinghe:selected-session:${encodeURIComponent(id)}`
}

function readLocalSessions(id = userId.value) {
  // 从浏览器本地缓存恢复指定联调用户的会话列表，后端仍是消息数据的最终来源。
  try {
    const data = JSON.parse(localStorage.getItem(sessionStorageKey(id)) || '[]')
    return Array.isArray(data) ? data : []
  } catch {
    return []
  }
}

function saveLocalSessions() {
  // 保存会话摘要和当前选中会话，刷新页面后可以快速恢复工作台状态。
  localStorage.setItem(sessionStorageKey(), JSON.stringify(sessions.value))
  localStorage.setItem(selectedStorageKey(), selectedSessionId.value)
}

function showNotice(message, kind = 'error') {
  // 显示带自动消失计时的统一提示，避免请求错误散落在各个业务函数中。
  notice.value = message
  noticeKind.value = kind
  clearTimeout(noticeTimer)
  noticeTimer = setTimeout(() => {
    notice.value = ''
  }, 4200)
}

const { csrfToken, ensureCsrfToken, buildRequestHeaders, readApiResponse, apiGet, apiRequest } = useApi({
  onUnauthorized: () => {
    clearAuthenticatedState()
    userDialogOpen.value = true
    showNotice('登录已过期，请重新登录')
  },
  onForbidden: (message) => showNotice(message),
})

function clearAuthenticatedState() {
  account.value = null
  userId.value = ''
  workspaceMode.value = 'service'
  sessions.value = []
  selectedSessionId.value = ''
  messages.value = []
  loadedSessionId.value = ''
  historyHasMore.value = false
  cart.value = []
  customerOrders.value = []
  selectedOrder.value = null
  csrfToken.value = ''
  storeProducts.value = []
  storeOrders.value = []
  storeProductPage.value = 0
  storeOrderPage.value = 0
  storeProductHasMore.value = false
  storeOrderHasMore.value = false
}

async function loadCart() {
  const rows = await apiGet('/api/cart')
  cart.value = rows
  cartQuantityDrafts.value = rows.reduce((drafts, line) => {
    drafts[line.skuId] = line.quantity
    return drafts
  }, {})
}

async function loadCustomerOrders(orderNoToLoad = '') {
  const value = orderNoToLoad.trim()
  if (!value) {
    selectedOrder.value = null
    return
  }
  try {
    selectedOrder.value = await apiGet(`/api/orders/${encodeURIComponent(value)}`)
  } catch (error) {
    selectedOrder.value = null
    showNotice(`订单读取失败：${error.message}`)
  }
}

async function loadCommerceProducts() {
  commerceLoading.value = true
  try {
    const query = new URLSearchParams()
    if (commerceKeyword.value.trim()) query.set('keyword', commerceKeyword.value.trim())
    commerceProducts.value = await apiGet(`/api/customer-service/products${query.size ? `?${query}` : ''}`)
    if (selectedProduct.value && !commerceProducts.value.some((product) => product.id === selectedProduct.value.id)) {
      selectedProduct.value = null
    }
  } catch (error) {
    showNotice(`商品读取失败：${error.message}`)
  } finally {
    commerceLoading.value = false
  }
}

async function refreshCommerce() {
  // 顾客工作台的刷新入口统一处理购物车和商品搜索的失败状态，避免产生未处理请求。
  try {
    await Promise.all([loadCart(), loadCommerceProducts()])
  } catch (error) {
    showNotice(`顾客数据刷新失败：${error.message}`)
  }
}

async function loadProductDetail(product) {
  productDetailLoading.value = true
  try {
    selectedProduct.value = await apiGet(`/api/customer-service/products/${encodeURIComponent(product.id)}`)
  } catch (error) {
    showNotice(`商品详情读取失败：${error.message}`)
  } finally {
    productDetailLoading.value = false
  }
}

async function loadStoreWorkspace(productPage = storeProductPage.value, orderPage = storeOrderPage.value) {
  if (!isStoreEmployee.value) {
    showNotice('店铺工作台仅对店铺员工开放')
    return
  }
  storeLoading.value = true
  try {
    const [productResult, orderResult] = await Promise.all([
      apiGet(`/api/store/products?page=${productPage}&size=50`),
      apiGet(`/api/orders/store?page=${orderPage}&size=50`),
    ])
    const productRows = productResult.items
    const orderRows = orderResult.items
    productEditDrafts.value = productRows.reduce((drafts, entry) => {
      const skuId = entry.product.id
      drafts[skuId] = productEditDrafts.value[skuId] || {
        name: entry.product.name || '',
        description: entry.product.description || '',
        price: entry.product.price,
      }
      return drafts
    }, {})
    shipmentDrafts.value = orderRows.reduce((drafts, entry) => {
      const orderNoValue = entry.order.orderNo
      drafts[orderNoValue] = shipmentDrafts.value[orderNoValue] || { company: '', trackingNo: '' }
      return drafts
    }, {})
    storeProducts.value = productRows
    storeOrders.value = orderRows
    storeProductPage.value = productPage
    storeOrderPage.value = orderPage
    storeProductHasMore.value = productResult.hasMore
    storeOrderHasMore.value = orderResult.hasMore
  } catch (error) {
    showNotice(`店铺数据读取失败：${error.message}`)
  } finally {
    storeLoading.value = false
  }
}

async function runCommerce(action, successMessage) {
  try {
    await action()
    if (successMessage) showNotice(successMessage, 'success')
    return true
  } catch (error) {
    showNotice(error.message)
    return false
  }
}

async function loadCurrentAccount() {
  try {
    const current = await apiGet('/api/auth/me', { handleUnauthorized: false })
    account.value = current
    userId.value = current.userId
    authForm.value.username = current.username || ''
    workspaceMode.value = current.role === 'STORE_EMPLOYEE' ? 'store' : 'service'
    storeProductPage.value = 0
    storeOrderPage.value = 0
    return current
  } catch (error) {
    if (!account.value) userDialogOpen.value = true
    return null
  }
}

async function submitAuthentication() {
  const username = authForm.value.username.trim()
  const password = authForm.value.password
  if (!username || password.length < 8) {
    showNotice('请输入用户名和至少 8 位密码')
    return
  }
  authLoading.value = true
  try {
    await ensureCsrfToken()
    if (authMode.value === 'register') {
      await apiRequest('/api/auth/register', { method: 'POST', body: { username, password }, handleUnauthorized: false })
    }
    const current = await apiRequest('/api/auth/login', { method: 'POST', body: { username, password }, handleUnauthorized: false })
    account.value = current
    userId.value = current.userId
    workspaceMode.value = current.role === 'STORE_EMPLOYEE' ? 'store' : 'service'
    authForm.value.password = ''
    userDialogOpen.value = false
    sessions.value = readLocalSessions()
    const lastSelected = localStorage.getItem(selectedStorageKey())
    selectedSessionId.value = sessions.value.some((item) => item.sessionId === lastSelected)
      ? lastSelected
      : sessions.value[0]?.sessionId || ''
    messages.value = []
    loadedSessionId.value = ''
    saveLocalSessions()
    if (selectedSessionId.value) await loadHistory(selectedSessionId.value, { quiet: true })
    if (current.role === 'STORE_EMPLOYEE') await loadStoreWorkspace()
    showNotice(authMode.value === 'register' ? '注册并登录成功' : '登录成功', 'success')
    if (workspaceMode.value === 'commerce') await refreshCommerce()
  } catch (error) {
    showNotice(`${authMode.value === 'register' ? '注册' : '登录'}失败：${error.message}`)
  } finally {
    authLoading.value = false
  }
}

async function logout() {
  try {
    await logoutSafely(apiRequest, () => {
      clearAuthenticatedState()
      userDialogOpen.value = true
      authMode.value = 'login'
      authForm.value.password = ''
    })
  } catch (error) {
    if (error.message) showNotice(`退出失败：${error.message}`)
  }
}

async function addToCart(skuId) {
  await runCommerce(async () => {
    await apiRequest('/api/cart/items', { method: 'POST', body: { skuId, quantity: 1 } })
    await loadCart()
  }, '已加入购物车')
}

async function setCartQuantity(line, quantity) {
  const succeeded = await runCommerce(async () => {
    if (quantity < 1) await apiRequest(`/api/cart/items/${line.skuId}`, { method: 'DELETE' })
    else await apiRequest(`/api/cart/items/${line.skuId}`, { method: 'PUT', body: { quantity } })
    await loadCart()
  })
  if (!succeeded) cartQuantityDrafts.value[line.skuId] = line.quantity
}

async function submitCartQuantity(line) {
  const quantity = Number(cartQuantityDrafts.value[line.skuId])
  if (!Number.isInteger(quantity) || quantity < 1 || quantity > 99) {
    cartQuantityDrafts.value[line.skuId] = line.quantity
    showNotice('商品数量必须是 1 到 99 的整数')
    return
  }
  await setCartQuantity(line, quantity)
}

async function removeFromCart(line) {
  await runCommerce(async () => {
    await apiRequest(`/api/cart/items/${line.skuId}`, { method: 'DELETE' })
    await loadCart()
  }, '商品已从购物车移除')
}

async function clearCart() {
  if (!window.confirm('确定要清空购物车吗？')) return
  await runCommerce(async () => {
    await apiRequest('/api/cart', { method: 'DELETE' })
    await loadCart()
  }, '购物车已清空')
}

async function checkoutCart() {
  await runCommerce(async () => {
    const orders = await apiRequest('/api/orders/checkout', {
      method: 'POST',
      headers: { 'Idempotency-Token': `checkout-${crypto.randomUUID()}` },
    })
    const parent = orders.find((item) => !item.parentOrderNo) || orders[0]
    await loadCart()
    await loadCustomerOrders(parent?.orderNo || '')
    showNotice('结算完成，订单待支付', 'success')
  })
}

async function payOrder(orderNoValue) {
  await runCommerce(async () => {
    await apiRequest(`/api/orders/${encodeURIComponent(orderNoValue)}/pay`, {
      method: 'POST',
      headers: { 'Idempotency-Token': `pay-${crypto.randomUUID()}` },
    })
    await loadCustomerOrders(orderNoValue)
    showNotice('支付成功', 'success')
  })
}

async function cancelOrder(orderNoValue) {
  await runCommerce(async () => {
    await apiRequest(`/api/orders/${encodeURIComponent(orderNoValue)}/cancel`, { method: 'POST' })
    await loadCustomerOrders(orderNoValue)
    showNotice('订单已取消', 'success')
  })
}

async function saveInventory(product) {
  const availableQuantity = Number(product.inventory.availableQuantity)
  if (!Number.isInteger(availableQuantity) || availableQuantity < 0) {
    showNotice('可用库存必须是非负整数')
    return
  }
  await runCommerce(async () => {
    await apiRequest(`/api/store/products/${product.product.id}/inventory`, {
      method: 'PUT', body: { availableQuantity },
    })
    await loadStoreWorkspace()
    showNotice('库存已更新', 'success')
  })
}

async function toggleProduct(product) {
  await runCommerce(async () => {
    const status = product.product.status === 'ON_SALE' ? 'OFF_SALE' : 'ON_SALE'
    await apiRequest(`/api/store/products/${product.product.id}/status`, { method: 'PUT', body: { status } })
    await loadStoreWorkspace()
  }, '商品状态已更新')
}

async function saveProduct(product) {
  const draft = productEditDrafts.value[product.product.id]
  const price = Number(draft?.price)
  if (!draft?.name?.trim() || !Number.isFinite(price) || price < 0) {
    showNotice('商品名称不能为空，价格必须是非负数字')
    return
  }
  await runCommerce(async () => {
    await apiRequest(`/api/store/products/${product.product.id}`, {
      method: 'PUT',
      body: {
        name: draft.name.trim(),
        description: draft.description || '',
        price,
      },
    })
    await loadStoreWorkspace()
  }, '商品资料已更新')
}

async function createStoreProduct() {
  await runCommerce(async () => {
    const product = { ...newProduct.value, skuId: Number(newProduct.value.skuId), price: Number(newProduct.value.price), availableQuantity: Number(newProduct.value.availableQuantity) }
    await apiRequest('/api/store/products', { method: 'POST', body: product })
    newProduct.value = { skuId: '', name: '', description: '', price: '', availableQuantity: 0 }
    await loadStoreWorkspace(0, storeOrderPage.value)
    showNotice('商品已创建', 'success')
  })
}

async function shipStoreOrder(order) {
  const draft = shipmentDrafts.value[order.orderNo] || {}
  await runCommerce(async () => {
    await apiRequest(`/api/orders/${encodeURIComponent(order.orderNo)}/ship`, {
      method: 'POST', body: { company: draft.company || '', trackingNo: draft.trackingNo || '' },
    })
    await loadStoreWorkspace()
    showNotice('订单已发货', 'success')
  })
}

function setWorkspaceMode(mode) {
  if (!account.value) {
    userDialogOpen.value = true
    showNotice('请先登录后使用工作台')
    return
  }
  if (mode === 'store' && !isStoreEmployee.value) {
    showNotice('店铺工作台仅对店铺员工开放')
    return
  }
  if (mode !== 'store' && !isCustomer.value) {
    showNotice('此工作台仅对顾客开放')
    return
  }
  workspaceMode.value = mode
  if (mode === 'commerce') {
    void refreshCommerce()
  }
  if (mode === 'store') loadStoreWorkspace()
}

async function createSession() {
  // 创建后端客服会话，并同步更新前端本地会话导航。
  if (!account.value) {
    showNotice('请先登录')
    userDialogOpen.value = true
    return null
  }

  isCreatingSession.value = true
  try {
    await ensureCsrfToken()
    const response = await fetch('/api/customer-service/sessions', {
      method: 'POST',
      credentials: 'include',
      headers: buildRequestHeaders({}, 'POST'),
    })
    const session = await readApiResponse(response)
    const item = {
      sessionId: session.sessionId,
      title: '新会话',
      updatedAt: session.createdAt || new Date().toISOString(),
    }
    sessions.value = [item, ...sessions.value.filter((entry) => entry.sessionId !== item.sessionId)]
    selectedSessionId.value = item.sessionId
    messages.value = []
    loadedSessionId.value = item.sessionId
    historyHasMore.value = false
    saveLocalSessions()
    closeMobileSessions()
    await scrollToBottom()
    return item
  } catch (error) {
    showNotice(`创建会话失败：${error.message}`)
    return null
  } finally {
    isCreatingSession.value = false
  }
}

async function loadHistory(sessionId, { quiet = false } = {}) {
  // Only a confirmed missing/foreign session may remove local navigation state.
  const requestVersion = ++historyRequestVersion
  isLoadingHistory.value = true
  if (loadedSessionId.value !== sessionId) {
    messages.value = []
    historyHasMore.value = false
  }
  try {
    const history = await apiGet(`/api/customer-service/sessions/${encodeURIComponent(sessionId)}/messages`)
    if (requestVersion !== historyRequestVersion || selectedSessionId.value !== sessionId) return
    messages.value = Array.isArray(history) ? history : []
    loadedSessionId.value = sessionId
    historyHasMore.value = messages.value.length === 100
    const firstUserMessage = messages.value.find((item) => item.messageType === 'USER')
    const entry = sessions.value.find((item) => item.sessionId === sessionId)
    if (entry && firstUserMessage && entry.title === '新会话') {
      entry.title = firstUserMessage.content.slice(0, 24)
      saveLocalSessions()
    }
  } catch (error) {
    if (requestVersion !== historyRequestVersion) return
    if (!quiet) showNotice(`读取会话失败：${error.message}`)
    if (shouldForgetSession(error)) {
      sessions.value = sessions.value.filter((item) => item.sessionId !== sessionId)
      selectedSessionId.value = sessions.value[0]?.sessionId || ''
      messages.value = []
      loadedSessionId.value = ''
      historyHasMore.value = false
      saveLocalSessions()
      if (selectedSessionId.value) await loadHistory(selectedSessionId.value, { quiet: true })
    }
  } finally {
    if (requestVersion === historyRequestVersion) {
      isLoadingHistory.value = false
      if (loadedSessionId.value === sessionId) await scrollToBottom()
    }
  }
}

async function loadOlderHistory() {
  if (!historyHasMore.value || historyLoadingMore.value || !messages.value.length || !selectedSessionId.value) return
  const sessionId = selectedSessionId.value
  const before = messages.value[0].messageId
  const list = messageList.value
  const previousHeight = list?.scrollHeight || 0
  historyLoadingMore.value = true
  try {
    const older = await apiGet(`/api/customer-service/sessions/${encodeURIComponent(sessionId)}/messages?before=${encodeURIComponent(before)}`)
    if (selectedSessionId.value !== sessionId || messages.value[0]?.messageId !== before) return
    messages.value = [...older, ...messages.value]
    historyHasMore.value = older.length === 100
    await nextTick()
    if (list) list.scrollTop += list.scrollHeight - previousHeight
  } catch (error) {
    showNotice(`读取更早消息失败：${error.message}`)
  } finally {
    historyLoadingMore.value = false
  }
}

async function selectSession(sessionId) {
  // 记录用户选择并加载对应会话的消息历史。
  if (sessionId === selectedSessionId.value && loadedSessionId.value === sessionId) {
    closeMobileSessions()
    return
  }
  selectedSessionId.value = sessionId
  saveLocalSessions()
  await loadHistory(sessionId)
  closeMobileSessions()
}

function closeMobileSessions() {
  mobileSessionsOpen.value = false
}

function closeMobileTools() {
  mobileToolsOpen.value = false
}

function updateSessionTitle(content) {
  const entry = sessions.value.find((item) => item.sessionId === selectedSessionId.value)
  if (!entry || entry.title !== '新会话') return
  entry.title = content.trim().slice(0, 24) || '新会话'
  entry.updatedAt = new Date().toISOString()
  sessions.value = [entry, ...sessions.value.filter((item) => item.sessionId !== entry.sessionId)]
  saveLocalSessions()
}

function parseSseMessage(response) {
  // 逐行解析客服接口的 SSE message/error 事件，兼容分块传输和多行 data。
  return new Promise(async (resolve, reject) => {
    let answer = null
    let eventName = 'message'
    let dataLines = []
    let buffer = ''

    const dispatch = () => {
      if (eventName === 'message' && dataLines.length) {
        const value = dataLines.join('\n')
        try {
          const parsed = JSON.parse(value)
          if (parsed && typeof parsed === 'object' && parsed.content) answer = parsed
        } catch {
          if (value && value !== 'DONE') answer = { content: value, messageType: 'ASSISTANT' }
        }
      }
      if (eventName === 'error' && dataLines.length) {
        reject(new Error(dataLines.join('\n')))
      }
      eventName = 'message'
      dataLines = []
    }

    const processLine = (line) => {
      if (!line) {
        dispatch()
        return
      }
      if (line.startsWith(':')) return
      const separator = line.indexOf(':')
      const field = separator < 0 ? line : line.slice(0, separator)
      let value = separator < 0 ? '' : line.slice(separator + 1)
      if (value.startsWith(' ')) value = value.slice(1)
      if (field === 'event') eventName = value
      if (field === 'data') dataLines.push(value)
    }

    try {
      if (!response.ok) {
        if (response.status === 401) {
          clearAuthenticatedState()
          userDialogOpen.value = true
        }
        const raw = await response.text()
        let body
        try { body = JSON.parse(raw) } catch { body = null }
        reject(new Error(body?.message || raw || `请求失败（${response.status}）`))
        return
      }

      if (!response.body) {
        const raw = await response.text()
        reject(new Error(raw || '客服暂时没有返回消息'))
        return
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      while (true) {
        const { done, value } = await reader.read()
        buffer += decoder.decode(value, { stream: !done })
        const lines = buffer.split(/\r?\n/)
        buffer = lines.pop() || ''
        lines.forEach(processLine)
        if (done) break
      }
      if (buffer) processLine(buffer)
      dispatch()
      if (!answer) reject(new Error('客服暂时没有返回消息'))
      else resolve(answer)
    } catch (error) {
      reject(error)
    }
  })
}

async function sendMessage() {
  // 发送用户消息并接收客服 SSE 回复，同时维护本地会话摘要和滚动位置。
  const content = draft.value.trim()
  if (!content || isSending.value) return
  if (!account.value) {
    showNotice('请先登录')
    userDialogOpen.value = true
    return
  }

  isSending.value = true
  draft.value = ''
  try {
    let session = currentSession.value
    if (!session) session = await createSession()
    if (!session) {
      draft.value = content
      return
    }

    const userMessage = {
      messageId: `local-${Date.now()}`,
      messageType: 'USER',
      content,
      createdAt: new Date().toISOString(),
    }
    messages.value.push(userMessage)
    updateSessionTitle(content)
    await scrollToBottom()

    await ensureCsrfToken()
    const response = await fetch(`/api/customer-service/sessions/${encodeURIComponent(session.sessionId)}/messages`, {
      method: 'POST',
      credentials: 'include',
      headers: buildRequestHeaders({
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
      }, 'POST'),
      body: JSON.stringify({ content }),
    })
    if (!response.ok) await readApiResponse(response)
    const answer = await parseSseMessage(response)
    messages.value.push({ ...answer, messageType: 'ASSISTANT', createdAt: answer.createdAt || new Date().toISOString() })
    const entry = sessions.value.find((item) => item.sessionId === session.sessionId)
    if (entry) {
      entry.updatedAt = new Date().toISOString()
      saveLocalSessions()
    }
  } catch (error) {
    showNotice(`消息发送失败：${error.message}`)
    messages.value.push({
      messageId: `error-${Date.now()}`,
      messageType: 'ERROR',
      content: '暂时无法连接客服服务，请检查后端服务状态后重试。',
      createdAt: new Date().toISOString(),
    })
  } finally {
    isSending.value = false
    await scrollToBottom()
  }
}

function handleComposerKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    sendMessage()
  }
}

function setPrompt(text) {
  draft.value = text
  document.querySelector('.composer-input')?.focus()
}

function openUserDialog() {
  authMode.value = account.value ? 'login' : authMode.value
  authForm.value.username = account.value?.username || authForm.value.username
  userDialogOpen.value = true
}

function openUserSettings() {
  openUserDialog()
}

async function lookupOrder() {
  // 调用订单查询工具并展示当前用户可访问的订单信息。
  const value = orderNo.value.trim()
  if (!value) {
    toolError.value = '请输入订单号'
    return
  }
  isQuerying.value = true
  toolError.value = ''
  orderResult.value = null
  logisticsResult.value = null
  try {
    orderResult.value = await apiGet(`/api/customer-service/orders/${encodeURIComponent(value)}`)
  } catch (error) {
    toolError.value = error.message
  } finally {
    isQuerying.value = false
  }
}

async function lookupLogistics() {
  // 调用物流查询工具，订单归属和未同步状态由后端负责判断。
  const value = orderNo.value.trim()
  if (!value) {
    toolError.value = '请输入订单号'
    return
  }
  isQuerying.value = true
  toolError.value = ''
  logisticsResult.value = null
  orderResult.value = null
  try {
    logisticsResult.value = await apiGet(`/api/customer-service/orders/${encodeURIComponent(value)}/logistics`)
  } catch (error) {
    toolError.value = error.message
  } finally {
    isQuerying.value = false
  }
}

async function searchProducts() {
  // 搜索后端返回的在售商品列表，并在工具面板中展示结果。
  const keyword = productKeyword.value.trim()
  if (!keyword) {
    toolError.value = '请输入商品关键词'
    return
  }
  isQuerying.value = true
  toolError.value = ''
  products.value = []
  try {
    products.value = await apiGet(`/api/customer-service/products?keyword=${encodeURIComponent(keyword)}`)
  } catch (error) {
    toolError.value = error.message
  } finally {
    isQuerying.value = false
  }
}

function clearToolResults() {
  toolError.value = ''
  orderResult.value = null
  logisticsResult.value = null
  products.value = []
}

function updateScrollButton() {
  // 根据消息区域距底部的距离决定是否显示“回到底部”按钮。
  const element = messageList.value
  if (!element) {
    showScrollToBottom.value = false
    return
  }

  const distanceFromBottom = element.scrollHeight - element.scrollTop - element.clientHeight
  showScrollToBottom.value = element.scrollHeight > element.clientHeight + 24 && distanceFromBottom > 64
}

async function scrollToBottom({ smooth = false } = {}) {
  // 在消息更新后滚动到最新内容，用户手动上滑时保留查看历史的位置。
  await nextTick()
  if (!messageList.value) return
  if (smooth) {
    messageList.value.scrollTo({ top: messageList.value.scrollHeight, behavior: 'smooth' })
  } else {
    messageList.value.scrollTop = messageList.value.scrollHeight
  }
  updateScrollButton()
}

function formatTime(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(date)
}

function shortSessionId(value) {
  return value ? value.slice(0, 8).toUpperCase() : ''
}

function statusLabel(value) {
  const labels = {
    CREATED: '待付款',
    PENDING: '处理中',
    PAID: '已付款',
    SHIPPED: '已发货',
    COMPLETED: '已完成',
    CANCELLED: '已取消',
    PENDING_PAYMENT: '待付款',
    NOT_SYNCED: '未同步',
    IN_TRANSIT: '运输中',
    DELIVERED: '已签收',
  }
  return labels[value] || value || '暂无状态'
}

function intentLabel(intent) {
  const labels = {
    AGENT: '智能客服',
    FAQ_REFUND: '售后规则',
    FAQ_DELIVERY: '配送咨询',
    FAQ_PAYMENT: '支付咨询',
    FAQ_HUMAN: '人工客服',
    ORDER_QUERY: '订单查询',
    LOGISTICS_QUERY: '物流查询',
    ORDER_ACCESS_DENIED: '安全提醒',
    ORDER_NOT_FOUND: '订单查询',
    UNSUPPORTED: '客服回复',
  }
  return labels[intent] || '客服回复'
}

function openTool(tool) {
  // 切换订单或商品查询工具，并清理上一个工具的结果。
  activeTool.value = tool
  clearToolResults()
  closeMobileTools()
}

watch(messages, () => {
  if (!historyLoadingMore.value && !isLoadingHistory.value) void scrollToBottom()
}, { deep: true })

onMounted(async () => {
  // 页面初始化时先恢复服务端 Session，再恢复该账户的本地会话导航。
  authReady.value = false
  const current = await loadCurrentAccount()
  if (current) {
    sessions.value = readLocalSessions()
    const lastSelected = localStorage.getItem(selectedStorageKey())
    selectedSessionId.value = sessions.value.some((item) => item.sessionId === lastSelected)
      ? lastSelected
      : sessions.value[0]?.sessionId || ''
    if (selectedSessionId.value) await loadHistory(selectedSessionId.value, { quiet: true })
    if (current.role === 'STORE_EMPLOYEE') await loadStoreWorkspace()
  }
  authReady.value = true
})
</script>

<template>
  <div v-if="authReady" class="workspace">
    <button v-if="mobileSessionsOpen || mobileToolsOpen" class="scrim" aria-label="关闭侧栏" @click="closeMobileSessions(); closeMobileTools()"></button>

    <SessionSidebar :account="account" :workspace-mode="workspaceMode" :cart-count="cart.length"
      :sessions="sessions" :selected-session-id="selectedSessionId" :is-creating-session="isCreatingSession"
      :mobile-open="mobileSessionsOpen" @mode="setWorkspaceMode" @create-session="createSession"
      @select-session="selectSession" @settings="openUserSettings" />

    <main class="chat-column">
      <header class="topbar">
        <div class="topbar-heading">
          <button class="icon-button mobile-menu-button" type="button" aria-label="打开会话列表" @click="mobileSessionsOpen = true"><Menu :size="19" /></button>
          <div>
            <div class="breadcrumb"><span>星河印象城</span><span class="breadcrumb-slash">/</span><strong>{{ workspaceTitle }}</strong></div>
            <div class="topbar-caption">多店铺交易 · 统一服务</div>
          </div>
        </div>
        <div class="topbar-actions">
          <div class="api-status"><span class="status-dot"></span><span>代理目标</span><code>8081</code></div>
          <button class="icon-button tools-toggle" type="button" title="查询工具" aria-label="打开查询工具" @click="mobileToolsOpen = true"><PanelRight :size="18" /></button>
        </div>
      </header>

      <div v-if="notice" class="notice" :class="`notice-${noticeKind}`" role="status">
        <CircleHelp :size="16" />
        <span>{{ notice }}</span>
        <button class="icon-button icon-button-small" type="button" aria-label="关闭提示" @click="notice = ''"><X :size="15" /></button>
      </div>

      <div v-if="workspaceMode === 'service'" class="conversation-heading">
        <div class="conversation-title-group">
          <div class="conversation-icon"><Headphones :size="18" /></div>
          <div class="conversation-title-copy">
            <h1>{{ currentSession?.title || '新建客服会话' }}</h1>
            <div class="conversation-subtitle">
              <span class="conversation-status-dot"></span>
              {{ currentSession ? `会话 ${shortSessionId(currentSession.sessionId)}` : '接待中' }}
              <span class="meta-divider">·</span>
              <span>星河印象城客服</span>
            </div>
          </div>
        </div>
        <div class="conversation-actions">
          <button class="secondary-button" type="button" :disabled="isCreatingSession" @click="createSession">
            <LoaderCircle v-if="isCreatingSession" class="spin" :size="16" />
            <Plus v-else :size="16" />
            <span>新会话</span>
          </button>
          <button class="icon-button mobile-tool-button" type="button" title="查询工具" aria-label="打开查询工具" @click="mobileToolsOpen = true"><PanelRight :size="18" /></button>
        </div>
      </div>

      <section v-if="workspaceMode === 'commerce'" class="commerce-panel">
        <div class="commerce-toolbar">
          <div><h1>顾客交易</h1><p>{{ displayUser }} · {{ cart.length }} 件购物车商品</p></div>
          <button class="lookup-secondary" type="button" :disabled="commerceLoading" @click="refreshCommerce"><LoaderCircle v-if="commerceLoading" class="spin" :size="15" /><Search v-else :size="15" />刷新</button>
        </div>
        <div class="commerce-layout">
          <section class="commerce-section">
            <div class="commerce-section-heading"><h2>商品</h2><form class="commerce-search" @submit.prevent="loadCommerceProducts"><input v-model="commerceKeyword" aria-label="搜索商品" placeholder="搜索在售商品"><button class="icon-button icon-button-small" aria-label="搜索" type="submit"><Search :size="15" /></button></form></div>
            <div v-if="commerceLoading" class="commerce-empty"><LoaderCircle class="spin" :size="18" />正在读取</div>
            <div v-else-if="!commerceProducts.length" class="commerce-empty">没有匹配的在售商品</div>
            <article v-for="product in commerceProducts" :key="product.id" class="commerce-product">
              <div class="commerce-item-icon"><ShoppingBag :size="17" /></div>
              <div class="commerce-product-copy"><strong>{{ product.name }}</strong><span>SKU {{ product.id }} · 店铺 {{ product.storeId }}</span><small>{{ product.description || '暂无商品描述' }}</small></div>
              <div class="commerce-product-action"><b>¥{{ product.price }}</b><div class="commerce-product-buttons"><button class="lookup-secondary" type="button" :disabled="productDetailLoading" @click="loadProductDetail(product)">详情</button><button class="lookup-primary" type="button" @click="addToCart(product.id)"><CirclePlus :size="14" />加入</button></div></div>
            </article>
            <div v-if="selectedProduct" class="product-detail-card">
              <div class="product-detail-heading"><div><strong>{{ selectedProduct.name }}</strong><span>SKU {{ selectedProduct.id }} · 店铺 {{ selectedProduct.storeId }}</span></div><button class="icon-button icon-button-small" type="button" aria-label="关闭商品详情" @click="selectedProduct = null"><X :size="14" /></button></div>
              <p>{{ selectedProduct.description || '暂无商品描述' }}</p>
              <div class="product-detail-price">¥{{ selectedProduct.price }} <span>{{ statusLabel(selectedProduct.status) }}</span></div>
            </div>
          </section>
          <section class="commerce-section">
            <div class="commerce-section-heading"><h2>购物车</h2><strong class="section-count">{{ cart.length }}</strong></div>
            <div v-if="!cart.length" class="commerce-empty">购物车为空</div>
            <article v-for="line in cart" :key="line.skuId" class="cart-line">
              <div class="commerce-product-copy"><strong>{{ line.productName }}</strong><span>SKU {{ line.skuId }} · ¥{{ line.unitPrice }} / 件</span></div>
              <div class="quantity-control"><button type="button" :aria-label="`减少${line.productName}数量`" :disabled="line.quantity <= 1" @click="setCartQuantity(line, line.quantity - 1)">−</button><input v-model="cartQuantityDrafts[line.skuId]" class="quantity-input" type="number" min="1" max="99" step="1" :aria-label="`输入${line.productName}数量`" title="请输入 1 到 99 的整数" @change="submitCartQuantity(line)" @keydown.enter.prevent="$event.target.blur()" /><button type="button" :aria-label="`增加${line.productName}数量`" :disabled="line.quantity >= 99" @click="setCartQuantity(line, line.quantity + 1)">+</button></div>
              <div class="cart-line-actions"><b>¥{{ line.lineAmount }}</b><button class="cart-remove-button" type="button" :aria-label="`删除${line.productName}`" title="删除商品" @click="removeFromCart(line)"><Trash2 :size="14" /></button></div>
            </article>
            <div v-if="cart.length" class="cart-summary"><button class="cart-clear-button" type="button" @click="clearCart">一键清空全部商品</button><span>合计</span><strong>¥{{ cart.reduce((sum, line) => sum + Number(line.lineAmount), 0).toFixed(2) }}</strong><button class="lookup-primary" type="button" @click="checkoutCart">结算</button></div>
          </section>
          <section class="commerce-section order-detail-section">
            <div class="commerce-section-heading"><h2>订单详情</h2><form class="commerce-search" @submit.prevent="loadCustomerOrders(orderLookup)"><input v-model="orderLookup" aria-label="订单编号" placeholder="输入订单编号"><button class="icon-button icon-button-small" aria-label="查询订单" type="submit"><Search :size="15" /></button></form></div>
            <div v-if="selectedOrder" class="order-detail">
              <strong class="order-number">{{ selectedOrder.order.orderNo }}</strong><span class="result-status">{{ statusLabel(selectedOrder.order.status) }}</span>
              <div class="detail-subheading">店铺订单</div>
              <article v-for="child in selectedOrder.childOrders" :key="child.orderNo" class="order-child"><strong>{{ child.orderNo }}</strong><span>{{ statusLabel(child.status) }} · ¥{{ child.amount }}</span></article>
              <div v-if="selectedOrder.items?.length" class="detail-subheading">商品明细</div>
              <article v-for="item in selectedOrder.items" :key="`${item.orderNo}-${item.skuId}`" class="order-child"><span>SKU {{ item.skuId }} × {{ item.quantity }}</span><strong>¥{{ item.lineAmount }}</strong></article>
              <div class="order-actions" v-if="selectedOrder.order.status === 'PENDING_PAYMENT'"><button class="lookup-primary" type="button" @click="payOrder(selectedOrder.order.orderNo)">支付订单</button><button class="lookup-secondary" type="button" @click="cancelOrder(selectedOrder.order.orderNo)">取消订单</button></div>
            </div>
            <div v-else class="commerce-empty">结算后可查看订单，或输入订单编号查询</div>
          </section>
        </div>
      </section>

      <section v-else-if="workspaceMode === 'store'" class="commerce-panel store-panel">
        <div class="commerce-toolbar">
          <div><h1>店铺工作台</h1><p>商品库存、订单与物流管理</p></div>
          <div class="store-toolbar-actions"><span>所属店铺：{{ account?.storeId }}</span><button class="lookup-secondary" type="button" :disabled="storeLoading" @click="loadStoreWorkspace()"><LoaderCircle v-if="storeLoading" class="spin" :size="15" /><Search v-else :size="15" />刷新</button></div>
        </div>
        <section class="commerce-section store-create-section">
          <div class="commerce-section-heading"><h2>新增商品</h2></div>
          <form class="product-create-form" @submit.prevent="createStoreProduct">
            <label>SKU<input v-model="newProduct.skuId" type="number" min="1" required></label><label>商品名称<input v-model="newProduct.name" required maxlength="120"></label><label>描述<input v-model="newProduct.description" maxlength="500"></label><label>价格<input v-model="newProduct.price" type="number" min="0" step="0.01" required></label><label>可用库存<input v-model="newProduct.availableQuantity" type="number" min="0" required></label><button class="lookup-primary" type="submit"><Plus :size="14" />创建商品</button>
          </form>
        </section>
        <div class="store-content-grid">
          <section class="commerce-section"><div class="commerce-section-heading"><h2>商品与库存</h2><strong class="section-count">{{ storeProducts.length }}</strong></div>
            <div v-if="!storeProducts.length" class="commerce-empty">暂无商品</div>
            <article v-for="entry in storeProducts" :key="entry.product.id" class="store-product-row"><div class="commerce-product-copy"><strong>{{ entry.product.name }}</strong><span>SKU {{ entry.product.id }} · {{ statusLabel(entry.product.status) }}</span><small>锁定 {{ entry.inventory.lockedQuantity }} · 已售 {{ entry.inventory.soldQuantity }}</small><label class="store-edit-field">商品资料<input v-model="productEditDrafts[entry.product.id].name" maxlength="120"></label><label class="store-edit-field">描述<input v-model="productEditDrafts[entry.product.id].description" maxlength="500"></label><label class="store-edit-field">价格<input v-model="productEditDrafts[entry.product.id].price" type="number" min="0" step="0.01"></label></div><label class="inventory-input">可用<input v-model.number="entry.inventory.availableQuantity" type="number" min="0"></label><div class="store-product-actions"><button class="lookup-secondary" type="button" @click="saveProduct(entry)">资料</button><button class="lookup-secondary" type="button" @click="saveInventory(entry)">库存</button><button class="lookup-secondary" type="button" @click="toggleProduct(entry)">{{ entry.product.status === 'ON_SALE' ? '下架' : '上架' }}</button></div></article>
            <div class="store-pagination"><button type="button" :disabled="storeLoading || storeProductPage === 0" @click="loadStoreWorkspace(storeProductPage - 1, storeOrderPage)">上一页</button><span>第 {{ storeProductPage + 1 }} 页</span><button type="button" :disabled="storeLoading || !storeProductHasMore" @click="loadStoreWorkspace(storeProductPage + 1, storeOrderPage)">下一页</button></div>
          </section>
          <section class="commerce-section"><div class="commerce-section-heading"><h2>店铺订单</h2><strong class="section-count">{{ storeOrders.length }}</strong></div>
            <div v-if="!storeOrders.length" class="commerce-empty">暂无店铺订单</div>
            <article v-for="entry in storeOrders" :key="entry.order.orderNo" class="store-order-row"><div class="store-order-heading"><strong>{{ entry.order.orderNo }}</strong><span class="result-status">{{ statusLabel(entry.order.status) }}</span></div><div class="store-order-meta">店铺 {{ entry.order.storeId }} · ¥{{ entry.order.amount }} · {{ entry.order.userId }}</div><div v-if="entry.logistics" class="store-order-meta">{{ entry.logistics.company }} · {{ entry.logistics.trackingNo }}</div><form v-else-if="entry.order.status === 'PAID'" class="shipment-form" @submit.prevent="shipStoreOrder(entry.order)"><input v-model="shipmentDrafts[entry.order.orderNo].company" placeholder="承运商" required><input v-model="shipmentDrafts[entry.order.orderNo].trackingNo" placeholder="运单号" required><button class="lookup-primary" type="submit"><Truck :size="14" />发货</button></form></article>
            <div class="store-pagination"><button type="button" :disabled="storeLoading || storeOrderPage === 0" @click="loadStoreWorkspace(storeProductPage, storeOrderPage - 1)">上一页</button><span>第 {{ storeOrderPage + 1 }} 页</span><button type="button" :disabled="storeLoading || !storeOrderHasMore" @click="loadStoreWorkspace(storeProductPage, storeOrderPage + 1)">下一页</button></div>
          </section>
        </div>
      </section>

      <section v-if="workspaceMode === 'service'" ref="messageList" class="message-list" tabindex="0" aria-live="polite" aria-label="客服消息，可滚动查看历史内容" @scroll="updateScrollButton">
        <div v-if="isLoadingHistory" class="loading-history"><LoaderCircle class="spin" :size="18" />正在加载会话</div>
        <template v-else-if="messages.length">
          <button v-if="historyHasMore" class="history-more-button" type="button" :disabled="historyLoadingMore" @click="loadOlderHistory">
            <LoaderCircle v-if="historyLoadingMore" class="spin" :size="15" />加载更早消息
          </button>
          <div class="day-divider"><span>当前会话</span></div>
          <article v-for="message in messages" :key="message.messageId" class="message-row" :class="message.messageType === 'USER' ? 'message-row-user' : 'message-row-assistant'">
            <div v-if="message.messageType !== 'USER'" class="message-avatar"><Bot :size="17" /></div>
            <div class="message-body">
              <div class="message-meta">
                <strong>{{ message.messageType === 'USER' ? displayUser : '星河客服' }}</strong>
                <span>{{ formatTime(message.createdAt) }}</span>
                <span v-if="message.intent && message.messageType !== 'USER' && message.messageType !== 'ERROR'" class="intent-label">{{ intentLabel(message.intent) }}</span>
              </div>
              <div class="message-bubble" :class="{ 'message-bubble-user': message.messageType === 'USER', 'message-bubble-error': message.messageType === 'ERROR' }">{{ message.content }}</div>
            </div>
            <div v-if="message.messageType === 'USER'" class="message-avatar message-avatar-user"><UserRound :size="17" /></div>
          </article>
          <article v-if="isSending" class="message-row message-row-assistant">
            <div class="message-avatar"><Bot :size="17" /></div>
            <div class="message-body">
              <div class="message-meta"><strong>星河客服</strong><span>正在处理</span></div>
              <div class="typing-bubble"><i></i><i></i><i></i></div>
            </div>
          </article>
        </template>
        <div v-else class="welcome-state">
          <div class="welcome-emblem"><Sparkles :size="23" /></div>
          <div class="welcome-kicker">CUSTOMER SERVICE</div>
          <h2>您好，欢迎联系星河印象城</h2>
          <p>我可以协助查询订单、商品和物流，也能为您解答常见售后问题。</p>
          <div class="suggestion-list">
            <button type="button" @click="setPrompt('我想了解退款规则')"><span class="suggestion-icon"><ClipboardList :size="16" /></span><span>了解退款规则</span><ArrowUpRight :size="15" /></button>
            <button type="button" @click="setPrompt('请帮我查询订单状态')"><span class="suggestion-icon"><PackageSearch :size="16" /></span><span>查询订单状态</span><ArrowUpRight :size="15" /></button>
            <button type="button" @click="setPrompt('我想查询商品')"><span class="suggestion-icon"><ShoppingBag :size="16" /></span><span>搜索商场商品</span><ArrowUpRight :size="15" /></button>
          </div>
        </div>
      </section>

      <button
        v-if="workspaceMode === 'service' && showScrollToBottom"
        class="scroll-to-bottom"
        type="button"
        aria-label="回到底部"
        title="回到底部"
        @click="scrollToBottom({ smooth: true })"
      >
        <ChevronDown :size="17" />
      </button>

      <footer v-if="workspaceMode === 'service'" class="composer-area">
        <div class="composer-wrap">
          <textarea
            v-model="draft"
            class="composer-input"
            rows="1"
            maxlength="1000"
            placeholder="输入订单问题或售后咨询…"
            aria-label="输入客服消息"
            @keydown="handleComposerKeydown"
          ></textarea>
          <div class="composer-bottom">
            <div class="composer-hint"><span class="composer-live-dot"></span><span>客服在线</span><span class="hint-separator">·</span><span>Enter 发送</span></div>
            <div class="composer-controls">
              <span class="character-count">{{ draft.length }}/1000</span>
              <button class="send-button" type="button" aria-label="发送消息" :disabled="!canSend" @click="sendMessage">
                <LoaderCircle v-if="isSending" class="spin" :size="16" />
                <Send v-else :size="16" />
                <span>发送</span>
              </button>
            </div>
          </div>
        </div>
        <div class="composer-footnote">请勿在对话中发送银行卡密码等敏感信息</div>
      </footer>
    </main>

    <aside class="tools-sidebar" :class="{ 'mobile-open': mobileToolsOpen }">
      <div class="tools-header">
        <div>
          <div class="tools-overline">QUICK LOOKUP</div>
          <h2>业务查询</h2>
        </div>
        <button class="icon-button tools-close" type="button" aria-label="关闭查询工具" @click="closeMobileTools"><X :size="18" /></button>
      </div>

      <div class="tool-tabs" role="tablist" aria-label="业务查询类型">
        <button type="button" role="tab" :aria-selected="activeTool === 'order'" :class="{ 'tool-tab-active': activeTool === 'order' }" @click="openTool('order')"><ClipboardList :size="16" /><span>订单</span></button>
        <button type="button" role="tab" :aria-selected="activeTool === 'product'" :class="{ 'tool-tab-active': activeTool === 'product' }" @click="openTool('product')"><ShoppingBag :size="16" /><span>商品</span></button>
      </div>

      <div class="tool-panel" role="tabpanel">
        <template v-if="activeTool === 'order'">
          <div class="tool-description"><div class="tool-description-icon"><PackageSearch :size="18" /></div><div><strong>订单与物流</strong><span>查询当前用户的订单记录</span></div></div>
          <form class="lookup-form" @submit.prevent="lookupOrder">
            <label for="order-number">订单编号</label>
            <div class="input-with-icon"><Search :size="16" /><input id="order-number" v-model="orderNo" type="text" placeholder="输入订单号" autocomplete="off" /></div>
            <div class="lookup-actions">
              <button class="lookup-primary" type="submit" :disabled="isQuerying"><LoaderCircle v-if="isQuerying" class="spin" :size="15" /><Search v-else :size="15" /><span>查订单</span></button>
              <button class="lookup-secondary" type="button" :disabled="isQuerying" @click="lookupLogistics"><Truck :size="15" /><span>查物流</span></button>
            </div>
          </form>
          <div v-if="toolError" class="tool-error" role="alert">{{ toolError }}</div>
          <div v-if="orderResult" class="result-block">
            <div class="result-heading"><span class="result-check"><Check :size="14" /></span><span>订单信息</span><span class="result-status">{{ statusLabel(orderResult.status) }}</span></div>
            <div class="result-number">{{ orderResult.orderNo }}</div>
            <dl class="result-grid">
              <div><dt>商品 SKU</dt><dd>{{ orderResult.skuId }}</dd></div>
              <div><dt>购买数量</dt><dd>{{ orderResult.quantity }}</dd></div>
              <div><dt>订单金额</dt><dd>¥{{ orderResult.amount }}</dd></div>
            </dl>
          </div>
          <div v-if="logisticsResult" class="result-block">
            <div class="result-heading"><span class="result-check"><Check :size="14" /></span><span>物流进度</span><span class="result-status">{{ statusLabel(logisticsResult.status) }}</span></div>
            <div class="result-number">{{ logisticsResult.orderNo }}</div>
            <dl class="result-grid result-grid-one">
              <div><dt>承运公司</dt><dd>{{ logisticsResult.company || '待同步' }}</dd></div>
              <div><dt>运单号</dt><dd>{{ logisticsResult.trackingNo || '待同步' }}</dd></div>
              <div><dt>最新动态</dt><dd>{{ logisticsResult.description || '暂无物流动态' }}</dd></div>
            </dl>
          </div>
          <div v-if="!orderResult && !logisticsResult && !toolError" class="tool-empty"><div class="tool-empty-icon"><Clock3 :size="19" /></div><span>查询结果将在这里显示</span></div>
        </template>

        <template v-else>
          <div class="tool-description"><div class="tool-description-icon"><ShoppingBag :size="18" /></div><div><strong>商品搜索</strong><span>搜索商场在售商品</span></div></div>
          <form class="lookup-form" @submit.prevent="searchProducts">
            <label for="product-keyword">商品关键词</label>
            <div class="input-with-icon"><Search :size="16" /><input id="product-keyword" v-model="productKeyword" type="search" placeholder="名称或关键词" autocomplete="off" /></div>
            <button class="lookup-primary lookup-wide" type="submit" :disabled="isQuerying"><LoaderCircle v-if="isQuerying" class="spin" :size="15" /><Search v-else :size="15" /><span>搜索商品</span></button>
          </form>
          <div v-if="toolError" class="tool-error" role="alert">{{ toolError }}</div>
          <div v-if="products.length" class="product-results">
            <div class="product-results-head"><strong>搜索结果</strong><span>{{ products.length }} 件</span></div>
            <article v-for="product in products" :key="product.id" class="product-result">
              <div class="product-result-icon"><ShoppingBag :size="17" /></div>
              <div class="product-result-copy"><strong>{{ product.name }}</strong><span>店铺 {{ product.storeId }} · {{ product.status }}</span><small>{{ product.description || '暂无商品描述' }}</small></div>
              <b>¥{{ product.price }}</b>
            </article>
          </div>
          <div v-else-if="!toolError" class="tool-empty"><div class="tool-empty-icon"><Search :size="19" /></div><span>搜索结果将在这里显示</span></div>
        </template>
      </div>

      <div class="tools-footer"><span class="tool-footer-icon"><Activity :size="15" /></span><span>接口代理</span><code>/api → 8081</code></div>
    </aside>

    <AuthDialog v-if="userDialogOpen" :account="account" :mode="authMode" :loading="authLoading"
      :username="authForm.username" :password="authForm.password" @close="userDialogOpen = false"
      @submit="submitAuthentication" @logout="logout" @update:mode="authMode = $event"
      @update:username="authForm.username = $event" @update:password="authForm.password = $event" />
  </div>
  <div v-else class="auth-restoring" role="status" aria-live="polite">正在恢复登录会话…</div>
</template>
