# Descrierea și structura datelor

Datele sunt disponibile într-un bucket de S3: s3://ppcro-rr-noprod-app00176-development-s3-2/RAW/streaming.
În cadrul acestei căi există mai multe foldere:
- hackathon_meteo_data
- hackathon_park_data
- hackathon_wtg_data

Conectare la S3:
- aws_access_key_id = "AKIATCKATFIM6BVTEXPZ"
- aws_secret_access_key = "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"
- region = "eu-central-1"

## hackathon_meteo_data

Acesta conține fișiere împarțite pe zile ce semnifică data la care au fost primite datele de meteo dintr-o sursă externă. Aceste date descriu media vitezei vântului estimată la un anumit moment pentru locația unei turbine. Aceste date vor fi folosite în cazul în care viteza vântului din senzori nu este disponibilă.
În cadrul ficărei zile există un fișier parquet a cărui schemă este:

| Coloană | Definiție |
| -------- | ------- |
| "data_timestamp" | data la care a fost înregistrată valoarea |
| "fk_wtg_id" | identificatorul turbinei |
| "wind_speed_mean_5min" | viteza medie a vântului timp de 5 minute de la data_timestamp |
| "etl_ts" | data la care a fost procesat câmpul de sistemul automat |

Exemplu:

{"data_timestamp":1773966300000,"fk_wtg_id":"WP98FA99B8.TID67229D69","wind_speed_mean_5min":"5.96"}


## hackathon_park_data

Acesta conține fișiere împărțite pe zile ce semnifică data la care au fost primite datele de senzori.
În cadrul ficărei zile există mai multe fișiere parquet ce conțin date de senzori structurate pe 4 coloane:

| Coloană | Definiție |
| -------- | ------- |
| "datetime_current_value" | data la care a fost înregistrată valoarea |
| "current_value" | valoarea care a fost înregistrată |
| "tag_name" | tag-ul, un identificator al semnalului senzorului |
| "etl_ts" | data la care a fost procesat câmpul de sistemul automat |

Exemplu:

{"datetime_current_value":1773989168000,"current_value":1842.3285408216361,"tag_name":"WP98FA99B8.TID374A8025.act_pwt","etl_ts":1773990299000}

### Structura TAG-ului

Fiecare semnal primit va avea o structură ierarhică și uniformă, care permite ca in denumirea TAG-ului să se regăsească informații despre par eolian, turbină și senzor.
 
Structura TAG–ului este compusă din două secțiuni principale:
1. Prefix variabil  
2. Sufix invariant

Numele complet este constituit de unirea celor două secțiuni.  
Prefixul variabil are scopul de a identifica parcul si turbina analizată.
Sufixul invariant are scopul de a defini ce reprezintă exact semnalul (de exemplu o măsură de curent, temperatura etc.). Odată identificată semnificația semnalului, acesta poate fi alocat în interiorul containerului creat de prefix.  
Desigur, o anumită secțiune de proces (prefixul) poate conține, de asemenea, mai mult de un singur semnal, precum și un semnal poate fi alocat în mai multe secțiuni diferite de proces.  
 
De exemplu, luați în considerare următoarele două nume de TAG:  
- WP98FA99B8.TID374A8025.act_pwt
- WP98FA99B8.TID30112302.act_pwt
 
Prima parte (pana la primul punct) este identificatorul parcului eolian, urmat de ce-a de-a doua parte până la urmatorul punct ce relevă identificatorul turbinei.
Sufixul identifică semnalul, în acest exemplu puterea activa.

### Descrierea senzorilor

Sufixele pot varia de la o turbina la alta, lista exhaustiva a lor este:
| Sufix | Definiție |
| -------- | ------- |
| "act_pwt" | puterea activă (instantanee) a turbinei |
| "wd_spd" | viteza vântului |
| "conv_t" | temperatură convertor |
| "gearbox_t" | temperatură cutie de viteze |
| "gen1_t" | temperatură rulment generator |
| "gen2_t" | temperatură rulment generator |
| "transformer_t" | temperatură transformator |
| "turbine_t" | temperatura ambientală a turbinei |

Unitățile de măsură pentru puterea activă pot varia între turbine, majoritatea în KWh.
Viteza vântului este măsurată în km/h, iar temeraturile sunt exprimate în grade Celsius.

Senzorii pot avea erori, erorile le putem identifica prin coduri fixe, si anume valorile primite nu conțin zecimale.

## hackathon_wtg_data

Această locație conține un singur fisier parquet. El reprezintă metadate ale turbinelor cu privire la tehnologia folosita și locația geografică.

| Coloană | Definiție |
| -------- | ------- |
| "pk_wtg_id" | identificator unic |
| "fk_wtg_model_id" | tehnologia/modelul turbinei |
| "fk_park_id" | id parc eolian |
| "fk_turbine_id" | id turbină |
| "lat_y" | latitudine |
| "long_x" | longitudine |

Exemplu:
{"pk_wtg_id":"WPBC408F47.TID680B6FB0","fk_wtg_model_id":"NEXUS","fk_park_id":"WPBC408F47","fk_turbine_id":"TID680B6FB0","lat_y":46.153406,"long_x":26.550755}

