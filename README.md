# PPC HackITall - Renewable Energy: From Weather to Power

Acest repository conține detaliile pentru dezvoltarea soluției.

## Contextul problemei

Este vineri seara. Operatorul tău de parcuri eoliene are active în mai multe regiuni. Prognoza meteo anunță: rafale puternice și front rece. 

Un parc eolian este format din mai multe turbine care, în funcție de viteza vântului, au o anumită putere activă. Fiecare turbină conține mai mulți senzori care definesc diferite informații precum viteza vântului, puterea activă, temperatura pe anumite componente. 

Parcurile eoliene sunt administrate de Operatorul Sistemului de Transport (TSO). Ei asigura transportul energiei de la parcuri la rețelele de distribuție. 

Datele “near-real-time” ale senzorilor vor fi livrate într-un datalake (S3) la interval de 15 minute. 

Totodată, va exista un set de metadate statice ce detaliază natura și scopul senzorilor. 

O altă componentă a datelor de intrare va fi reprezentată de date dintr-o sursă externa de meteo, menită să înlocuie datele de senzor in cazul in care acestea nu există (de exemplu, datorita unei opriri a turbinei). 

In paralel, TSO cere: 

- Raport operațional la 09:00 zilnic pentru zilele precedente (număr stabilit de utilizator): producție pe tip, pe parcuri, disponibilitate (availability) și statusul turbinei. Producția (MWh) = putere (MW) × timp (ore). Restul de KPIs vor fi compuși de participant. 

- Un “near-real-time data graph” (la nivel de 15 minute și orar) pentru temperatură, viteza vântului și putere activă. Scopul este de a detecta: data gaps (senzori care nu mai raportează), discrepante între meteo si producție (ex. vânt mare, output 0 – e defect sau e o oprire anunțata?). 

Problema: pot exista date cu format neuniform, duplicate etc. Dacă raportarea e greșită, apar penalizări si decizii proaste (limitări, intervenții). 

 

## Misiunea hackathonului (24h) 

Obiectivul hackathonului: livrați un data product consumabil de business și un layer de microservicii care îl face “prod-ready”: 

- “near-real-time data graph” pentru temperatură, viteza vântului si producție 

- produce un raport “TSO-ready” la 09:00, 

- are reguli de calitate și alerte, astfel încât dispeceratul să aibă încredere în date. 

 

## Date disponibile (input) 

Turbine sensor data: viteza vântului, puterea activă, temperaturi ale senzorilor 

Plant metadata: tip, nume parcuri, locația turbinelor.

External Meteo data: viteza vântului.

Data dictionary: ce reprezintă datele de intrare.

Mai multe detalii gasiți in DataDictionary.md

## Evaluare

### TRACK A — Energy Data Engineering (Control Room Warehouse) + Data Quality Control 

#### Objective 

Construiți un warehouse + pipelines + reguli DQ astfel încât rezultatele să fie corecte, reproductibile, auditabile. 

#### Deliverables (MVP)

- Construire data model (arhitectura solutiei high-level, ER diagram, relation database with results, KPIs etc) 

- Solution Documentation (Tables DDLs, procedures, Python Scripts etc) 

- Pipelines (Normalizează unități de măsură, duplicate) 

- Data Quality rules (Missing data, etc.)

Tech Stack: 

- Python sau orice echivalent

- Relational DB

- Orice altceva considerati necesar

### TRACK B — Microservices  

#### Scop 

Platformă web pentru administrare procesare date input(trackA) și vizualizare/monitorizare date procesate pentru KPI, grafice, poziționare pe hartă 

#### Objective 

Transformați output-ul din Track A intr-un produs consumabil: API-uri, job orchestration, alerting, și “operational UX”. 

Creați un API pentru un client extern pentru a expune informații din ultimele 15 minute legate de active power pentru o turbină. Acesta trebuie să fie securizat și monitorizat 

 
#### Deliverables (MVP): 

- Interfață administrare/vizualizare job/pipelines 

- Interfață multitenant – configurare access doar către anumite parcuri 

- Contract API client extern – informații utilizare și input/output ex 

- Audit trail (opțional)  

- Prezentare de KPI – diferite forme de vizualuri/grafice, spre exemplu pe hartă 

 
Tech Stack: 

- Java, Spring / Spring Boot 

- Typescript, Angular  

- Maven, npm 

- Relational DB 

- OpenAPI specs( opțional )  


## Questions

Daca ai nevoie de ajutor sau clarificări întreabă orice mentor!

## Notes

Have fun developing your solution and good luck!

## Nu uitați să urcați codul în github la fiecare 6 ore

