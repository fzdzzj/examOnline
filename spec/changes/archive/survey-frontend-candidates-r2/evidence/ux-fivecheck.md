UX 边界五查证据（面二） · revision 608adf5 (main)
方法: 逐页读 .page.vue 全文做五查（空态/加载态/错误反馈/表单校验/危险操作确认）+ grep 交叉
结论: 9 个新变更面逐页五查，既有页面网格复核。见 appended lines.
==== U-6 证据：exams/index 查询失败无 error 反馈 ====
 "grep: Alert|isError|errorText in teacher/*.vue (exams/index 的 Alert 仅在确认弹窗 :119/:140/:165)" 
exams/index.page.vue lines 272-292:
272: const { data, isFetching, refetch } = useQuery({
273:   queryKey: computed(
274:     () =>
275:       ['exams', pageNum.value, pageSize.value, debouncedTitle.value, statusFilter.value] as const
276:   ),
277:   queryFn: () =>
278:     unwrap<ExamResponse[]>(
279:       pageExams({
280:         client,
281:         throwOnError: true,
282:         query: {
283:           page: pageNum.value,
284:           size: pageSize.value,
285:           title: debouncedTitle.value || undefined,
286:           status: statusFilter.value ?? undefined,
287:         },
288:       })
289:     ),
290: });
291: 
292: const rows = computed<ExamResponse[]>(() => data.value ?? []);
293: 

==== U-7 证据：exams/create 编辑回填失败静默（全文件 0 Alert）====
grep -c '\\bAlert\\b' create.page.vue => 0
create.page.vue lines 181-224 (回填 useQuery + watch):
181: const { data: editDetail } = useQuery({
182:   queryKey: computed(() => ['exam-edit-detail', editExamId.value] as const),
183:   queryFn: () =>
184:     unwrap<ExamDetailResponse>(
185:       examDetailContract({
186:         client,
187:         throwOnError: true,
188:         path: { id: editExamId.value as number },
189:       })
190:     ),
191:   enabled: isEditMode,
192: });
193: 
194: /** antiCheatConfig 濂戠害绫诲瀷鏄?JsonNode锛坲nknown锛夛細鎸夊垱寤哄啓鍏ョ殑閿鍙栵紝缂哄け鏃跺洖钀芥柊寤洪粯璁ゅ€?*/
195: function readAntiCheatConfig(raw: unknown): { switchScreen: boolean; forbidCopy: boolean } {
196:   const cfg = (raw ?? {}) as { switchScreen?: unknown; forbidCopy?: unknown };
197:   return {
198:     switchScreen: cfg.switchScreen !== false,
199:     forbidCopy: cfg.forbidCopy === true,
200:   };
201: }
202: 
203: // 璇︽儏杩斿洖鍚庡洖濉竴娆★紱缂栬緫涓噸鏂版媺鍙栦笉瑕嗙洊鐢ㄦ埛宸叉敼鐨勮〃鍗?
204: const backfilled = ref(false);
205: watch(
206:   editDetail,
207:   (detail) => {
208:     if (isEditMode.value && detail && !backfilled.value) {
209:       backfilled.value = true;
210:       form.value.title = detail.title ?? '';
211:       form.value.description = detail.description ?? '';
212:       form.value.paperId = detail.paperId;
213:       form.value.classId = detail.classId;
214:       form.value.startTime = detail.startTime;
215:       form.value.endTime = detail.endTime;
216:       form.value.durationMinutes = detail.durationMinutes ?? 60;
217:       form.value.allowLateMinutes = detail.allowLateMinutes ?? 0;
218:       const antiCheat = readAntiCheatConfig(detail.antiCheatConfig);
219:       enableSwitchScreen.value = antiCheat.switchScreen;
220:       enableForbidCopy.value = antiCheat.forbidCopy;
221:     }
222:   },
223:   { immediate: true }
224: );
