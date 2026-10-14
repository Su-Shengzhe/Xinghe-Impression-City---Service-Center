<script setup>
import { LoaderCircle, UserRound, X } from 'lucide-vue-next'

defineProps({
  account: { type: Object, default: null },
  mode: { type: String, required: true },
  loading: { type: Boolean, required: true },
  username: { type: String, required: true },
  password: { type: String, required: true },
})
defineEmits(['close', 'submit', 'logout', 'update:mode', 'update:username', 'update:password'])
</script>

<template>
  <div class="dialog-backdrop" @click.self="account && $emit('close')">
    <section class="user-dialog" role="dialog" aria-modal="true" aria-labelledby="user-dialog-title">
      <div class="dialog-heading"><div class="dialog-icon"><UserRound :size="19" /></div><button v-if="account" class="icon-button" type="button" aria-label="关闭" @click="$emit('close')"><X :size="18" /></button></div>
      <h2 id="user-dialog-title">{{ account ? '账户设置' : '登录星河印象城' }}</h2>
      <p>{{ account ? '当前请求使用 Session Cookie 鉴权。' : '登录或注册后即可使用客服、购物车和订单服务。' }}</p>
      <form class="auth-form" @submit.prevent="$emit('submit')">
        <label for="auth-username">用户名</label>
        <input id="auth-username" :value="username" type="text" maxlength="64" autocomplete="username" required @input="$emit('update:username', $event.target.value)">
        <label for="auth-password">密码</label>
        <input id="auth-password" :value="password" type="password" minlength="8" maxlength="128" autocomplete="current-password" required @input="$emit('update:password', $event.target.value)">
        <div v-if="!account" class="auth-mode-switch"><button type="button" :class="{ 'auth-mode-active': mode === 'login' }" @click="$emit('update:mode', 'login')">登录</button><button type="button" :class="{ 'auth-mode-active': mode === 'register' }" @click="$emit('update:mode', 'register')">注册</button></div>
        <div class="dialog-actions"><button v-if="account" type="button" class="dialog-cancel" @click="$emit('close')">取消</button><button class="dialog-save" type="submit" :disabled="loading"><LoaderCircle v-if="loading" class="spin" :size="14" />{{ mode === 'register' ? '注册并登录' : '登录' }}</button></div>
      </form>
      <button v-if="account" type="button" class="logout-button" @click="$emit('logout')">退出登录</button>
    </section>
  </div>
</template>
