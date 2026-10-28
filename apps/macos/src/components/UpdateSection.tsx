// SPDX-License-Identifier: AGPL-3.0-only
import { useEffect, useState } from "react";
import { Download, RefreshCw, X, Check, RotateCw } from "lucide-react";
import { bridge } from "../bridge";
import { Button } from "./ui";
export interface UpdateState {
  phase: string; currentVersion: string; version?: string; notes?: string;
  message?: string; progress: number; size?: number;
}
export function UpdateSection() {
  const [s, set] = useState<UpdateState>({phase:"idle",currentVersion:"",progress:0});
  useEffect(() => {
    if (!bridge.updates) return;
    let active=true;
    const off=bridge.onUpdate?.(v=>{if(active)set(v);});
    void bridge.updates("state").then(v=>{if(active)set(v);}).catch(()=>{});
    return ()=>{active=false;off?.();};
  },[]);
  if (!bridge.updates) return null;
  const act=async (action:string)=>{try{set(await bridge.updates!(action));}catch(e){set(v=>({...v,phase:"error",message:e instanceof Error?e.message:"Не удалось обновить Лад"}));}};
  const busy=["checking","downloading","installing"].includes(s.phase);
  const title=s.phase==="available"?`Доступен Лад ${s.version}`:s.phase==="current"?"Установлена последняя версия":s.phase==="ready"?"Обновление загружено":s.phase==="downloading"?`Скачивание · ${s.progress}%`:s.phase==="checking"?"Проверяем GitHub…":s.phase==="installing"?"Подготовка к установке…":s.phase==="error"?"Не удалось обновить Лад":"Обновления";
  return <section className="settings-section update-section" aria-label="Обновления приложения">
    <h2>{title}</h2>
    <p className="settings-about">Лад {s.currentVersion} · стабильный выпуск</p>
    <div role="status" aria-live="polite">
      {s.phase==="error" && <p className="update-error">{s.message}</p>}
      {s.phase==="ready" && <p className="settings-about">Лад {s.version} готов к установке. Очередь и настройки сохранятся. Воспроизведение на этом устройстве прервётся при перезапуске.</p>}
      {s.phase==="downloading" && <><progress aria-label="Загрузка обновления" max={100} value={s.progress}/><p className="settings-about">Можно продолжать слушать музыку.</p></>}
    </div>
    {s.notes && ["available","ready"].includes(s.phase) && <div className="update-notes">{s.notes}</div>}
    <div className="update-actions">
      {s.phase==="available"?<Button icon={Download} onClick={()=>void act("download")}>Скачать обновление{s.size?` · ${Math.ceil(s.size/1048576)} МБ`:""}</Button>:s.phase==="ready"?<Button icon={RotateCw} onClick={()=>void act("install")}>Установить и перезапустить</Button>:<Button icon={s.phase==="current"?Check:RefreshCw} loading={busy} onClick={()=>void act("check")}>{s.phase==="installing"?"Устанавливаем…":s.phase==="checking"?"Проверяем…":"Проверить обновления"}</Button>}
      {["checking","downloading"].includes(s.phase) && <Button icon={X} variant="ghost" onClick={()=>void act("cancel")}>Отменить</Button>}
      {["available","ready"].includes(s.phase) && <Button variant="ghost" onClick={()=>void bridge.open("https://github.com/KiriDey0/Lad/releases")}>История версий</Button>}
    </div>
  </section>;
}
