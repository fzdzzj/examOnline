import { VueQueryPlugin } from '@tanstack/vue-query';
import { createApp } from 'vue';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';

import App from './App.vue';
import './tailwindcss.css';
import { queryClient } from './api/queryClient';
import router from './router';
import { store } from './store';

dayjs.locale('zh-cn');

const app = createApp(App);
app.use(store);
app.use(VueQueryPlugin, { queryClient });
app.use(router);
app.mount('#app');
