// src/dynamic-form.js
import {
  createApp,
  ref,
  reactive,
  computed,
  onMounted,
  defineComponent,
  shallowRef
} from "vue";
var PROXY_PREFIX = "/httpproxy";
function withProxy(absPath) {
  if (!absPath.startsWith("/")) return PROXY_PREFIX + "/" + absPath;
  return PROXY_PREFIX + absPath;
}
var manifestUrl = withProxy("/api/dynamic-form/manifest");
var submitUrl = withProxy("/api/dynamic-form/submit");
async function loadJson(path) {
  const r = await fetch(withProxy(path), { headers: { Accept: "application/json" } });
  if (!r.ok) throw new Error("HTTP " + r.status + " " + path);
  return r.json();
}
createApp({
  setup() {
    const loading = ref(true);
    const error = ref("");
    const manifest = ref(null);
    const schema = computed(() => manifest.value?.schema ?? null);
    const formData = reactive({});
    const fieldErrors = reactive({});
    const submitResult = ref("");
    const beforeFields = shallowRef(null);
    const afterFields = shallowRef(null);
    const fields = computed(
      () => schema.value && schema.value.fields ? schema.value.fields : []
    );
    function resetModel() {
      Object.keys(formData).forEach((k) => delete formData[k]);
      Object.keys(fieldErrors).forEach((k) => delete fieldErrors[k]);
      for (const f of fields.value) {
        if (f.type === "number")
          formData[f.id] = f.default != null ? Number(f.default) : null;
        else formData[f.id] = f.default != null ? String(f.default) : "";
      }
    }
    onMounted(async () => {
      try {
        const m = await fetch(manifestUrl, {
          headers: { Accept: "application/json" }
        }).then((r) => {
          if (!r.ok) throw new Error("HTTP " + r.status);
          return r.json();
        });
        manifest.value = m;
        resetModel();
        for (const a of m.attachments || []) {
          const d = await loadJson(a.optionsUrl);
          const comp = defineComponent(d.vueOptions);
          if (a.slot === "beforeFields") beforeFields.value = comp;
          if (a.slot === "afterFields") afterFields.value = comp;
        }
      } catch (e) {
        error.value = String(e.message || e);
      } finally {
        loading.value = false;
      }
    });
    function validate() {
      Object.keys(fieldErrors).forEach((k) => delete fieldErrors[k]);
      let ok = true;
      for (const f of fields.value) {
        const v = formData[f.id];
        if (f.required && (v === "" || v == null)) {
          fieldErrors[f.id] = "B\u1EAFt bu\u1ED9c";
          ok = false;
          continue;
        }
        if (f.type === "number" && v !== "" && v != null) {
          const n = Number(v);
          if (Number.isNaN(n)) {
            fieldErrors[f.id] = "Ph\u1EA3i l\xE0 s\u1ED1";
            ok = false;
            continue;
          }
          if (f.min != null && n < f.min) {
            fieldErrors[f.id] = "\u2265 " + f.min;
            ok = false;
          }
          if (f.max != null && n > f.max) {
            fieldErrors[f.id] = "\u2264 " + f.max;
            ok = false;
          }
        }
        if (f.maxLength && typeof v === "string" && v.length > f.maxLength) {
          fieldErrors[f.id] = "T\u1ED1i \u0111a " + f.maxLength + " k\xFD t\u1EF1";
          ok = false;
        }
      }
      return ok;
    }
    async function handleSubmit() {
      submitResult.value = "";
      if (!validate()) return;
      const payload = { ...formData };
      const r = await fetch(submitUrl, {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify(payload)
      });
      const text = await r.text();
      submitResult.value = r.ok ? text : "Error " + r.status + ": " + text;
    }
    const containerId = computed(() => manifest.value?.container?.id || "form-root");
    return {
      loading,
      error,
      manifest,
      schema,
      formData,
      fieldErrors,
      fields,
      handleSubmit,
      submitResult,
      manifestUrl,
      submitUrl,
      beforeFields,
      afterFields,
      containerId
    };
  },
  template: `
        <div class="form-container" :id="containerId">
          <h1>{{ schema?.title || 'Qu\u1EA3n l\xFD Merchants' }}</h1>
          <p class="meta">
            <strong>App:</strong> qu\u1EA3n l\xFD merchant (draft) \u2014 schema t\u1EEB <code>FormDefinitionJson</code> trong manifest
            <code>{{ manifestUrl }}</code>; slot codegen qua <code>FormCodegenServlet</code>;
            l\u01B0u draft POST <code>{{ submitUrl }}</code>. C\u1EA7n <code>HTTP_PROXY_TARGET_BASE</code> tr\u1ECF origin app khi d\xF9ng <code>/httpproxy</code>.
          </p>
          <p v-if="loading">\u0110ang t\u1EA3i manifest &amp; codegen\u2026</p>
          <p v-else-if="error" class="err">{{ error }}</p>
          <template v-else>
            <component v-if="beforeFields" :is="beforeFields" />
            <form @submit.prevent="handleSubmit">
              <div v-for="field in fields" :key="field.id">
                <label :for="field.id">{{ field.label }}</label>
                <input
                  v-if="field.type === 'text' || field.type === 'number'"
                  :id="field.id"
                  :type="field.type === 'number' ? 'number' : 'text'"
                  v-model="formData[field.id]"
                  :placeholder="field.placeholder || ''"
                  :required="field.required"
                  :min="field.min"
                  :max="field.max"
                  :maxlength="field.maxLength"
                />
                <select v-else-if="field.type === 'select'" :id="field.id" v-model="formData[field.id]" :required="field.required">
                  <option v-for="opt in field.options" :key="String(opt.value)" :value="opt.value">{{ opt.label }}</option>
                </select>
                <textarea
                  v-else-if="field.type === 'textarea'"
                  :id="field.id"
                  v-model="formData[field.id]"
                  :rows="field.rows || 3"
                  :placeholder="field.placeholder || ''"
                  :required="field.required"
                />
                <p v-if="fieldErrors[field.id]" class="err">{{ fieldErrors[field.id] }}</p>
              </div>
              <button type="submit">L\u01B0u draft merchant</button>
            </form>
            <component v-if="afterFields" :is="afterFields" />
          </template>
          <pre v-if="submitResult">{{ submitResult }}</pre>
        </div>
      `
}).mount("#app");
//# sourceMappingURL=dynamic-form.bundle.js.map
