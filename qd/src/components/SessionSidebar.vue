<script setup>
import { computed } from 'vue'
import { ChevronDown, LoaderCircle, MessageCircle, MessagesSquare, Plus, ShoppingCart, Store } from 'lucide-vue-next'

const props = defineProps({
  account: { type: Object, default: null },
  workspaceMode: { type: String, required: true },
  cartCount: { type: Number, required: true },
  sessions: { type: Array, required: true },
  selectedSessionId: { type: String, required: true },
  isCreatingSession: { type: Boolean, required: true },
  mobileOpen: { type: Boolean, required: true },
})
defineEmits(['mode', 'create-session', 'select-session', 'settings'])

const isCustomer = computed(() => props.account?.role === 'CUSTOMER')
const isStoreEmployee = computed(() => props.account?.role === 'STORE_EMPLOYEE')
const userMark = computed(() => (props.account?.username?.slice(0, 1) || 'U').toUpperCase())
const displayUser = computed(() => props.account?.username || '未登录')

function formatTime(value) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(date)
}
function shortSessionId(value) { return value ? value.slice(0, 8).toUpperCase() : '' }
</script>

<template>
  <aside class="session-sidebar" :class="{ 'mobile-open': mobileOpen }">
    <div class="brand-lockup">
      <div class="brand-mark"><Store :size="19" stroke-width="2.2" /></div>
      <div class="brand-copy"><span class="brand-title">星河印象城</span><span class="brand-subtitle">交易服务中心</span></div>
    </div>

    <div v-if="account" class="workspace-label">工作台</div>
    <button v-if="isCustomer" class="nav-item" :class="{ 'nav-item-active': workspaceMode === 'service' }" type="button" @click="$emit('mode', 'service')"><MessagesSquare :size="18" /><span>客服工作台</span><span v-if="workspaceMode === 'service'" class="nav-live-dot"></span></button>
    <button v-if="isCustomer" class="nav-item" :class="{ 'nav-item-active': workspaceMode === 'commerce' }" type="button" @click="$emit('mode', 'commerce')"><ShoppingCart :size="18" /><span>顾客交易</span><span v-if="cartCount" class="nav-count">{{ cartCount }}</span></button>
    <button v-if="isStoreEmployee" class="nav-item" :class="{ 'nav-item-active': workspaceMode === 'store' }" type="button" @click="$emit('mode', 'store')"><Store :size="18" /><span>店铺工作台</span></button>

    <div v-if="isCustomer" class="session-section-head"><span>最近会话</span><button class="icon-button icon-button-small" type="button" title="新建会话" aria-label="新建会话" :disabled="isCreatingSession" @click="$emit('create-session')"><LoaderCircle v-if="isCreatingSession" class="spin" :size="16" /><Plus v-else :size="17" /></button></div>
    <div v-if="isCustomer" class="session-list">
      <button v-for="session in sessions" :key="session.sessionId" type="button" class="session-item" :class="{ 'session-item-active': selectedSessionId === session.sessionId }" @click="$emit('select-session', session.sessionId)">
        <MessageCircle class="session-item-icon" :size="16" />
        <span class="session-item-copy"><span class="session-title">{{ session.title }}</span><span class="session-meta">{{ formatTime(session.updatedAt) }} · {{ shortSessionId(session.sessionId) }}</span></span>
      </button>
      <div v-if="!sessions.length" class="session-empty"><span class="empty-line"></span><span>暂无会话</span><button type="button" @click="$emit('create-session')">新建客服会话</button></div>
    </div>

    <div class="sidebar-footer"><div class="service-status"><span class="status-dot"></span><span>服务在线</span></div><button class="user-switch" type="button" @click="$emit('settings')"><span class="user-avatar">{{ userMark }}</span><span class="user-copy"><span>{{ account?.role === 'STORE_EMPLOYEE' ? '店铺员工' : account ? '顾客账户' : '未登录' }}</span><strong>{{ displayUser }}</strong></span><ChevronDown :size="15" /></button></div>
  </aside>
</template>
