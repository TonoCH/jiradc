# Project Information (18702) – distribúcia hodnoty v hierarchii

## Účel
Riešenie automaticky udržiava hodnotu poľa **Project Information** (`customfield_18702`,
Multiple choices select list) konzistentnú v celej hierarchii issues. Hodnota sa distribuuje
**zhora nadol** – z najvyššieho parenta k najnižšiemu childovi – a to pri vytvorení, úprave aj
zmazaní issue. Vynútenie prebieha na úrovni listenera, takže platí aj pre zmeny cez REST API,
nielen cez UI.

## Komponenty
Riešenie tvorí päť skriptov:

- **ProjectInformationListener** – globálny ScriptRunner listener. Zachytáva eventy a podľa
  Project Category rozhodne, ktorý režim sa použije, a zavolá príslušný handler.
- **ProjectInformationHandler** – abstraktná báza s celou spoločnou logikou (prechod hierarchiou,
  čítanie/zápis poľa, riadenie rekurzie, práca s override príznakom).
- **FuelProjectInformationHandler** – režim FUEL.
- **OtherProjectInformationHandler** – režim OTHER.
- **ProjectInformationBehaviour** – behaviour, ktorý na issues s parentom zamkne pole v UI
  (read-only). Ide len o pohodlie používateľa; samotné pravidlo vynucuje listener.

Handler sa vytvára cez factory `ProjectInformationHandler.create(type)` s povinným parametrom
`FUEL` alebo `OTHER`; factory podľa neho inštancuje správnu potomkovskú triedu (dedenie).

## Dva režimy distribúcie

### FUEL
Aktivuje sa len pre projekty, ktorých **Project Category = "FUEL Projects"** (overuje listener).
Hodnota parenta je vždy záväzná – akákoľvek používateľská úprava na childovi sa prepíše hodnotou
z parenta. Distribúciu nie je možné prerušiť.

### OTHER
Použije sa pre všetky ostatné projekty. Distribúcia ide takisto zhora nadol, ale môže byť
**prerušená zámernou voľbou používateľa**. Ak používateľ vedome nastaví na nejakom issue inú
hodnotu, toto issue sa označí ako „override" a stáva sa novým zdrojom pre svoj podstrom: zhora sa
už neprepisuje a jeho vlastná hodnota tečie ďalej nadol. Override sa eviduje ako issue entity
property na danom issue.

## Hierarchia
Parent sa hľadá v štandardnej hierarchii v tomto poradí: subtask → **Parent issue**, inak
**Epic Link** (`10001`), inak **Parent Link** (`10301`). Najvyšší parent (top issue) je prvé
issue smerom nahor, ktoré už nemá žiadnu z týchto väzieb – typicky Initiative, ale môže to byť aj
Feature, Epic, Story či Task. Childovia sa zisťujú cez subtasky a cez JQL na Epic/Parent Link.

## Správanie podľa eventu

### Create
Ak má nové issue parenta alebo epic link, prevezme jeho hodnotu poľa 18702. Issue bez parenta sa
berie ako koreň a ponechá si svoju hodnotu, kým väzba nevznikne.

### Update
Sleduje sa, či sa zmenilo pole 18702 alebo niektorá z väzieb:

- **Zmena 18702 na top issue** – hodnota sa distribuuje nadol na celý podstrom.
- **Zmena 18702 na non-top issue** – pri FUEL sa zmena zruší a hodnota sa vráti z parenta; pri
  OTHER sa zmena akceptuje, issue sa označí ako override a nová hodnota tečie nadol.
- **Zmena Epic Link / Parent Link** – issue znovu zdedí hodnotu z nového parenta (pri OTHER, ak
  nemá override, inak si override ponechá) a táto hodnota sa rozdistribuuje nadol.

### Delete
Ak mazané issue malo potomkov, pole 18702 sa na všetkých potomkoch vyčistí.

## Technické zabezpečenie
- **Servisný používateľ:** všetky skriptové zápisy bežia pod `jira.bot`. Vďaka tomu je jasné, že
  zmenu inicioval skript, a kto o ňu reálne požiadal, je dohľadateľné na najvyššej položke.
- **Bez rekurzie:** zápisy idú cez `updateIssue(..., DO_NOT_DISPATCH, false)`, takže skriptové
  zmeny znova nespustia listener. Navyše listener ignoruje eventy vyvolané samotným `jira.bot`.
- **Ochrana proti cyklom:** prechod hierarchiou používa množinu už spracovaných issue ID.
- **Bezpečnosť hodnôt:** hodnoty sa kopírujú podľa textovej hodnoty a znovu sa rozpoznávajú v
  kontexte cieľového issue. Ak by hodnota v kontexte cieľa neexistovala, krok sa preskočí (pole sa
  nezmaže). Zápis prebehne iba ak sa cieľová hodnota reálne líši od aktuálnej (žiadne zbytočné
  reindexy).
- **API vs behaviour:** behaviour zamkne pole len v UI; skutočné pravidlo (vrátane prepisu pri
  zmene cez API) vynucuje listener.

## Známe obmedzenia
- **Delete a potomkovia cez Epic/Parent Link:** event sa volá až po zmazaní. Subtasky sa mažú
  spolu s parentom, ale ak Jira odpojí Epic/Parent Link skôr, než dobehne dohľadanie potomkov,
  takíto potomkovia sa nemusia stihnúť vyčistiť. Pre tvrdú garanciu by bolo potrebné pre-delete
  riešenie.
- **OTHER a presun overridnutého issue:** override je „lepkavý" – po presune pod nový parent si
  issue ponechá svoju hodnotu. Ak by mal presun override rušiť, je to bodová úprava.
- **Multi-select kontexty:** predpokladá sa, že pole 18702 má na FUEL projektoch spoločný
  (globálny) kontext, aby boli rovnaké hodnoty dostupné naprieč projektmi.
