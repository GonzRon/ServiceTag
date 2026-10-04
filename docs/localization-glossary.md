# Localization glossary

The vocabulary every ServiceTag language pack uses (#102). A feature that adds strings uses these terms; a term
that is not here yet is added here first, in every shipped language, before the strings that use it. Rules and
workflow: [Localization](localization.md).

English (`res/values/`) is the source. The packs are: Spanish `es`, Portuguese `pt` (Brazilian usage), Simplified
Chinese `zh-Hans`, French `fr`, Japanese `ja`, Russian `ru`, German `de`, Italian `it`, Hindi `hi`.

## Never translated

| Token | Why |
|---|---|
| ServiceTag, NoteTag | product names |
| Home Assistant | product name |
| NFC, Wi-Fi, Ethernet, API, MCP, URL, PDF | technical names |
| `https://`, `http://`, entity ids (`input_boolean.hot_tub_season`), file names, model and serial numbers | values the owner types or reads off equipment |
| Anything the owner entered | names, notes, titles, borrowers, units: shown exactly as entered |

## Voice

Plain, calm, specific. Address the owner the way Android does in that language:

| | es | pt | zh-Hans | fr | ja | ru | de | it | hi |
|---|---|---|---|---|---|---|---|---|---|
| address | tú | você | 您 (sparingly; prefer no pronoun) | vous | polite です/ます; no pronoun | вы (lower case) | du | tu | आप |
| quotes around a UI name | “…” | “…” | “…” | « … » | 「…」 | «…» | „…“ | “…” | “…” |

When a sentence names another control ("choose ‘Only on this home Wi-Fi’"), it uses **that control's own
translation, verbatim**, inside the language's quotes. Status words that English writes in capitals (DUE, OVERDUE)
are capitals in languages that have them and plain in those that do not (zh-Hans, ja, hi).

## Core terms

| English | Meaning in ServiceTag | es | pt | zh-Hans | fr | ja | ru | de | it | hi |
|---|---|---|---|---|---|---|---|---|---|---|
| Asset | one piece of equipment with its own record and tags | equipo | equipamento | 设备 | équipement | 機器 | объект | Gerät | attrezzatura | उपकरण |
| child asset | an Asset that is part of another Asset | equipo incluido | equipamento incluído | 子设备 | équipement rattaché | 子機器 | дочерний объект | Untergerät | attrezzatura collegata | उप-उपकरण |
| Part of | an Asset's parent | Forma parte de | Faz parte de | 隶属于 | Fait partie de | 所属 | Входит в | Teil von | Fa parte di | इसका हिस्सा |
| tag / NFC tag | the sticker the phone scans | etiqueta NFC | etiqueta NFC | NFC 标签 | tag NFC | NFCタグ | NFC-метка | NFC-Tag | tag NFC | NFC टैग |
| scan | tap a tag with the phone | escanear | escanear | 扫描 | scanner | スキャン | сканировать | scannen | scansiona | स्कैन |
| write (a tag) | put the identity on a tag | escribir | gravar | 写入 | écrire | 書き込む | записать | beschreiben | scrivere | लिखें |
| maintenance | the work an Asset needs | mantenimiento | manutenção | 保养 | entretien | メンテナンス | обслуживание | Wartung | manutenzione | रखरखाव |
| schedule | one recurring maintenance task | plan | plano | 计划 | plan | スケジュール | график | Plan | piano | शेड्यूल |
| group | a schedule shared by several Assets | grupo | grupo | 组 | groupe | グループ | группа | Gruppe | gruppo | समूह |
| round | a group's current pass over its members | ronda | rodada | 轮次 | tour | ラウンド | цикл | Runde | giro | चक्र |
| due / DUE | the work is due now | pendiente / PENDIENTE | pendente / PENDENTE | 到期 | à faire / À FAIRE | 期限 | срок / СРОК | fällig / FÄLLIG | da fare / DA FARE | देय |
| due soon / DUE SOON | | próximo / PRÓXIMO | em breve / EM BREVE | 即将到期 | bientôt / BIENTÔT | まもなく期限 | скоро / СКОРО | bald fällig / BALD FÄLLIG | a breve / A BREVE | जल्द देय |
| overdue / OVERDUE | | vencido / VENCIDO | atrasado / ATRASADO | 已逾期 | en retard / EN RETARD | 期限切れ | просрочено / ПРОСРОЧЕНО | überfällig / ÜBERFÄLLIG | in ritardo / IN RITARDO | अतिदेय |
| deferred / DEFERRED | held back by the maintenance policy | aplazado / APLAZADO | adiado / ADIADO | 已推迟 | différé / DIFFÉRÉ | 延期 | отложено / ОТЛОЖЕНО | zurückgestellt / ZURÜCKGESTELLT | rinviato / RINVIATO | स्थगित |
| paused / PAUSED | | en pausa / EN PAUSA | pausado / PAUSADO | 已暂停 | en pause / EN PAUSE | 一時停止 | приостановлено / ПРИОСТАНОВЛЕНО | pausiert / PAUSIERT | in pausa / IN PAUSA | रुका हुआ |
| complete / done | record that the work was done | completar / hecho | concluir / feito | 完成 | terminer / fait | 完了 | выполнить / выполнено | erledigen / erledigt | completare / fatto | पूरा करें / पूरा |
| snooze | remind again later | posponer | adiar | 稍后提醒 | reporter | スヌーズ | отложить | zurückstellen | posticipa | स्नूज़ |
| reminder | a notification about due work | recordatorio | lembrete | 提醒 | rappel | リマインダー | напоминание | Erinnerung | promemoria | रिमाइंडर |
| meter | a running count such as engine hours | contador | medidor | 计数器 | compteur | メーター | счётчик | Zähler | contatore | मीटर |
| reading | one value read from a meter or gauge | lectura | leitura | 读数 | relevé | 測定値 | показание | Messwert | lettura | रीडिंग |
| baseline | the first meter reading a schedule counts from | lectura inicial | leitura inicial | 基准读数 | relevé de référence | 基準値 | исходное показание | Ausgangswert | lettura iniziale | आधार रीडिंग |
| Readings & actions | an Asset's measurement types and quick actions | Lecturas y acciones | Leituras e ações | 读数与操作 | Relevés et actions | 測定値とアクション | Показания и действия | Messwerte & Aktionen | Letture e azioni | रीडिंग और कार्य |
| quick action | a one-tap journal entry | acción rápida | ação rápida | 快捷操作 | action rapide | クイックアクション | быстрое действие | Schnellaktion | azione rapida | त्वरित कार्य |
| journal / history | the Asset's record of events | historial | histórico | 日志 | journal | 記録 | журнал | Protokoll | registro | लॉग |
| entry / event | one thing recorded in the journal | registro | registro | 记录 | entrée | 記録 | запись | Eintrag | voce | प्रविष्टि |
| operating season | when the Asset is in use | temporada de uso | temporada de uso | 使用季 | saison d'utilisation | 稼働シーズン | сезон эксплуатации | Betriebssaison | stagione di utilizzo | संचालन मौसम |
| in season / out of season | | en temporada / fuera de temporada | em temporada / fora de temporada | 使用季内 / 使用季外 | en saison / hors saison | シーズン中 / シーズン外 | в сезоне / вне сезона | in der Saison / außerhalb der Saison | in stagione / fuori stagione | मौसम में / मौसम से बाहर |
| maintenance break | a span with no routine work | pausa de mantenimiento | pausa de manutenção | 保养暂停期 | pause d'entretien | メンテナンス休止期間 | перерыв в обслуживании | Wartungspause | pausa di manutenzione | रखरखाव विराम |
| condition | operational, degraded or down | estado | condição | 状况 | état | 状態 | состояние | Zustand | condizione | स्थिति |
| operational / OPERATIONAL | | operativo | operacional | 正常运行 | opérationnel | 稼働中 | работает | betriebsbereit | operativo | चालू |
| degraded / DEGRADED | works, but not fully | degradado | degradado | 性能下降 | dégradé | 性能低下 | ухудшено | eingeschränkt | degradato | कमज़ोर |
| down / DOWN | does not work | fuera de servicio | parado | 停机 | en panne | 停止 | не работает | ausgefallen | fuori servizio | बंद |
| incident | a recorded fault | incidente | incidente | 故障 | incident | 障害 | неисправность | Störung | guasto | खराबी |
| health | the derived score from age and overdue work | salud | saúde | 健康度 | santé | 健全性 | здоровье | Gesundheit | salute | स्वास्थ्य |
| health subject | one thing health follows | indicador | indicador | 健康指标 | indicateur | 評価対象 | показатель | Kriterium | indicatore | संकेतक |
| service case | one problem followed through repair visits | caso de servicio | chamado de serviço | 服务工单 | dossier d'intervention | サービス案件 | сервисное обращение | Servicefall | pratica di assistenza | सेवा मामला |
| supply item | a product the equipment uses: filter, battery, chemical | suministro | insumo | 耗材 | fourniture | 消耗品 | расходный материал | Verbrauchsartikel | materiale di consumo | आपूर्ति सामग्री |
| installed component | a fitted part inside an Asset, with its own history | componente instalado | componente instalado | 已安装部件 | composant installé | 取り付け部品 | установленный компонент | Einbauteil | componente installato | लगा हुआ पुर्ज़ा |
| document | a file kept with a record | documento | documento | 文档 | document | ドキュメント | документ | Dokument | documento | दस्तावेज़ |
| attachment | a file, photo or document | adjunto | anexo | 附件 | pièce jointe | 添付ファイル | вложение | Anhang | allegato | अनुलग्नक |
| reference | a saved web address | referencia | referência | 参考链接 | référence | 参照 | веб-ссылка | Verweis | riferimento | संदर्भ |
| link | a URL | enlace | link | 链接 | lien | リンク | ссылка | Link | link | लिंक |
| Save as document | download a reference into a stored file | Guardar como documento | Salvar como documento | 另存为文档 | Enregistrer comme document | ドキュメントとして保存 | Сохранить как документ | Als Dokument speichern | Salva come documento | दस्तावेज़ के रूप में सहेजें |
| warranty | | garantía | garantia | 保修 | garantie | 保証 | гарантия | Garantie | garanzia | वारंटी |
| lend / loan | hand an Asset to someone for a while | prestar / préstamo | emprestar / empréstimo | 借出 | prêter / prêt | 貸し出す / 貸出 | одолжить / выдача | verleihen / Verleih | prestare / prestito | उधार देना / उधार |
| borrower | who has it | prestado a | emprestado para | 借用人 | emprunteur | 借り手 | кому выдано | Entleiher | in prestito a | उधारकर्ता |
| due back | the date it should return | devolver antes del | devolver até | 应归还日期 | à rendre le | 返却予定日 | вернуть до | Rückgabe bis | da restituire entro | वापसी की तारीख |
| retire / retired | take out of service for good, keeping history | retirar / retirado | desativar / desativado | 退役 / 已退役 | retirer du service / retiré | 使用終了 | списать / списано | ausmustern / ausgemustert | dismettere / dismesso | सेवा से हटाएँ / सेवा से हटाया गया |
| archive / archived | hide without retiring | archivar / archivado | arquivar / arquivado | 归档 / 已归档 | archiver / archivé | アーカイブ | в архив / в архиве | archivieren / archiviert | archiviare / archiviato | संग्रहित करें / संग्रहित |
| Replace asset | retire one Asset in favour of a new one | Sustituir equipo | Substituir equipamento | 替换设备 | Remplacer l'équipement | 機器を置き換える | Заменить объект | Gerät ersetzen | Sostituisci attrezzatura | उपकरण बदलें |
| Transfer Pack | a file that hands Assets to another phone | paquete de transferencia | pacote de transferência | 转移包 | pack de transfert | 移行パック | пакет передачи | Übergabepaket | pacchetto di trasferimento | ट्रांसफ़र पैक |
| transferred out | handed to another owner | transferido | transferido | 已转出 | transféré | 移管済み | передано | übergeben | trasferito | ट्रांसफ़र किया गया |
| backup / restore | | copia de seguridad / restaurar | backup / restaurar | 备份 / 恢复 | sauvegarde / restaurer | バックアップ / 復元 | резервная копия / восстановить | Sicherung / wiederherstellen | backup / ripristinare | बैकअप / पुनर्स्थापित करें |
| category | the kind of Asset | categoría | categoria | 类别 | catégorie | カテゴリ | категория | Kategorie | categoria | श्रेणी |
| Developer API | the local workstation interface | API para desarrolladores | API para desenvolvedores | 开发者 API | API développeur | 開発者 API | API для разработчиков | Entwickler-API | API per sviluppatori | डेवलपर API |
| pairing code | the code a workstation enters | código de emparejamiento | código de pareamento | 配对码 | code d'appairage | ペアリングコード | код сопряжения | Kopplungscode | codice di associazione | पेयरिंग कोड |
| sync | read Home Assistant's state | sincronizar | sincronizar | 同步 | synchroniser | 同期 | синхронизировать | synchronisieren | sincronizzare | सिंक करें |
| access token | Home Assistant's long-lived token | token de acceso | token de acesso | 访问令牌 | jeton d'accès | アクセストークン | токен доступа | Zugriffstoken | token di accesso | एक्सेस टोकन |

## Android's own words

Permission and settings text names Android's controls the way Android names them in that language:

| English | es | pt | zh-Hans | fr | ja | ru | de | it | hi |
|---|---|---|---|---|---|---|---|---|---|
| Location (permission) | Ubicación | Local | 位置信息 | Position | 位置情報 | Местоположение | Standort | Posizione | जगह की जानकारी |
| Precise / Approximate | Precisa / Aproximada | Precisa / Aproximada | 精确 / 大致 | Exacte / Approximative | 正確 / おおよそ | Точное / Приблизительное | Genau / Ungefähr | Precisa / Approssimativa | सटीक / अनुमानित |
| Notifications | Notificaciones | Notificações | 通知 | Notifications | 通知 | Уведомления | Benachrichtigungen | Notifiche | सूचनाएं |
| app settings | ajustes de la aplicación | configurações do app | 应用设置 | paramètres de l'application | アプリの設定 | настройки приложения | App-Einstellungen | impostazioni dell'app | ऐप की सेटिंग |
| Contacts | Contactos | Contatos | 通讯录 | Contacts | 連絡先 | Контакты | Kontakte | Contatti | संपर्क |
