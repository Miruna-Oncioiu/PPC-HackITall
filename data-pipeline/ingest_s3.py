import pandas as pd
import s3fs
import sqlite3
import time
import numpy as np
import traceback
import os
import argparse
from datetime import datetime, timedelta
from dotenv import load_dotenv

load_dotenv()

storage_options = {
    "key": os.getenv("AWS_ACCESS_KEY"),
    "secret": os.getenv("AWS_SECRET_KEY"),
    "client_kwargs": {'region_name': os.getenv("AWS_REGION")}
}
S3_BASE_PATH = "s3://ppcro-rr-noprod-app00176-development-s3-2/RAW/streaming/"

# =============================================================================
# CURBE OEM PER MODEL — interpolate liniar P = P1 + (v-v1)*(P2-P1)/(v2-v1)
# Calibrate pe datele reale (non-anomaly) din raw_history
# =============================================================================
OEM_CURVES = {
    "VORTEX": [
        (0,0),(2,0),(3,37),(4,68),(5,120),(6,190),(7,280),
        (8,380),(9,480),(10,580),(11,650),(12,700),(13,720),
        (14,730),(15,735),(25,735),(26,0)
    ],
    "HELIOS": [
        (0,0),(2,0),(3,84),(4,117),(5,200),(6,310),(7,420),
        (8,500),(9,560),(10,600),(11,620),(12,630),(13,635),
        (14,640),(15,640),(25,640),(26,0)
    ],
    "NEXUS": [
        (0,0),(2,27),(3,44),(4,106),(5,200),(6,350),(7,500),
        (8,650),(9,780),(10,870),(11,930),(12,960),(13,975),
        (14,980),(15,985),(25,985),(26,0)
    ]
}

NOMINAL_POWER_KW = {"VORTEX": 735, "HELIOS": 640, "NEXUS": 985}
CUT_IN_SPEED     = {"VORTEX": 3.0, "HELIOS": 3.0, "NEXUS": 2.5}

# Praguri termice [nominal_max, warning, critical] in Celsius
THERMAL_THRESHOLDS = {
    "conv_t":        [45, 60, 75],
    "gearbox_t":     [55, 70, 85],
    "gen1_t":        [65, 80, 95],
    "gen2_t":        [65, 80, 95],
    "transformer_t": [55, 70, 85],
    "turbine_t":     [35, 50, 60],
}


def interpoleaza_oem(viteza, model):
    """Interpolează puterea OEM pentru viteza dată (m/s) → kW."""
    curba = OEM_CURVES.get(model, OEM_CURVES["NEXUS"])
    if viteza is None or pd.isna(viteza) or viteza <= 0:
        return 0.0
    for i in range(len(curba) - 1):
        v1, p1 = curba[i]
        v2, p2 = curba[i + 1]
        if v1 <= viteza <= v2:
            return p1 + (viteza - v1) * (p2 - p1) / (v2 - v1) if v2 != v1 else p1
    return 0.0


def calculeaza_risc_termic(row_dict):
    """
    Indice risc termic [0-100].
    0-33: normal, 34-66: warning, 67-100: critic
    Returnează (scor_global, dict_per_componenta)
    """
    componente = {}
    scoruri = []
    for col, (nom, warn, crit) in THERMAL_THRESHOLDS.items():
        val = row_dict.get(col)
        if val is None or (isinstance(val, float) and np.isnan(val)):
            continue
        if val <= nom:
            scor = 0.0
        elif val <= warn:
            scor = 33.0 * (val - nom) / (warn - nom)
        elif val <= crit:
            scor = 33.0 + 34.0 * (val - warn) / (crit - warn)
        else:
            scor = min(100.0, 67.0 + 33.0 * (val - crit) / max(crit - warn, 1))
        componente[col] = round(scor, 1)
        scoruri.append(scor)
    if not scoruri:
        return None, {}
    return round(sum(scoruri) / len(scoruri), 1), componente


def repara_schema_bazei_de_date():
    try:
        conn = sqlite3.connect('../history.db')
        cur  = conn.cursor()
        cur.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='raw_history'")
        if cur.fetchone():
            cur.execute("PRAGMA table_info(raw_history)")
            columns = [info[1] for info in cur.fetchall()]
            nou = {
                'status_color':    "TEXT DEFAULT 'GREEN'",
                'sensor_warnings': "TEXT DEFAULT ''",
                'is_anomaly':      "INTEGER DEFAULT 0",
                'anomaly_reason':  "TEXT DEFAULT 'Functionare normala'",
                'thermal_risk':    "REAL DEFAULT NULL",
            }
            for col, defn in nou.items():
                if col not in columns:
                    print(f"  [Schema] Adaug: {col}")
                    cur.execute(f"ALTER TABLE raw_history ADD COLUMN {col} {defn}")
        conn.commit()
        conn.close()
    except Exception as e:
        print(f"Eroare schema: {e}")


def calculeaza_eficienta_si_teoreticul(df):
    """Putere teoretică prin interpolarea curbei OEM per model."""
    if 'fk_wtg_model_id' in df.columns:
        df['power_theoretical'] = df.apply(
            lambda r: interpoleaza_oem(r.get('wd_spd'), r.get('fk_wtg_model_id', 'NEXUS')),
            axis=1
        )
    else:
        cond = [(df['wd_spd'] < 5), (df['wd_spd'] >= 5)]
        val  = [0, (df['wd_spd'] ** 3) * 0.05]
        df['power_theoretical'] = np.select(cond, val, default=0)

    df['power_efficiency_pct'] = np.where(
        df['power_theoretical'] > 10,
        np.clip((df['act_pwt'] / df['power_theoretical']) * 100, 0, 150),
        0
    )
    return df


def calculeaza_status(df):
    """
    Status color:
    GREEN  — produce energie normal
    ORANGE — nu produce din cauza vântului insuficient (sub cut-in)
    YELLOW — mentenanță necesară (fluctuații / eficiență scăzută)
    RED    — eroare critică / scurtcircuit
    """
    def status_row(r):
        model   = r.get('fk_wtg_model_id', 'NEXUS')
        cut_in  = CUT_IN_SPEED.get(model, 3.0)
        nominal = NOMINAL_POWER_KW.get(model, 1000)
        power   = r.get('act_pwt')
        wind    = r.get('wd_spd')
        anomaly = r.get('is_anomaly', 0)
        reason  = r.get('anomaly_reason', '')
        warn    = r.get('sensor_warnings', '')
        eff     = r.get('power_efficiency_pct', 0)

        if power is None or pd.isna(power):
            return 'RED'
        if power < -50:
            return 'RED'
        if power > nominal * 1.5:
            return 'RED'
        if anomaly == 1 and reason in ['Scurtcircuit', 'Eroare senzor']:
            return 'RED'
        # Fără vânt suficient → portocaliu
        if wind is not None and not pd.isna(wind) and wind < cut_in and abs(power) < 10:
            return 'ORANGE'
        # Eficiență scăzută sau warnings → galben
        if (wind is not None and not pd.isna(wind) and wind >= cut_in
                and eff < 40 and power < nominal * 0.1):
            return 'YELLOW'
        if warn and len(str(warn)) > 5:
            return 'YELLOW'
        return 'GREEN'

    df['status_color'] = df.apply(status_row, axis=1)
    return df


# =============================================================================
# RAPORT OPERATIONAL COMPLET
# =============================================================================
def genereaza_raport_operational(zile_in_urma=1):
    print(f"\n{'='*60}")
    print(f"RAPORT OPERATIONAL — ultimele {zile_in_urma} zile")
    print(f"{'='*60}")

    if not os.path.exists('../history.db'):
        print("! history.db lipsește."); return

    repara_schema_bazei_de_date()
    conn = sqlite3.connect('../history.db')

    timp_acum  = datetime.now()
    timp_start = (timp_acum - timedelta(days=zile_in_urma)).strftime('%Y-%m-%d %H:%M:%S')
    print(f"  Interval: {timp_start} → {timp_acum.strftime('%Y-%m-%d %H:%M:%S')}")

    try:
        df = pd.read_sql(f"""
            SELECT park_id, turbine_id, fk_wtg_model_id, timestamp,
                   act_pwt, wd_spd, power_theoretical, power_efficiency_pct,
                   status_color, is_anomaly, anomaly_reason, sensor_warnings,
                   conv_t, gearbox_t, gen1_t, gen2_t, transformer_t, turbine_t,
                   lat_y, long_x
            FROM raw_history WHERE timestamp >= '{timp_start}'
        """, conn)

        if df.empty:
            print("! Nicio dată în intervalul selectat.")
            conn.close(); return

        print(f"  Înregistrări citite: {len(df):,}")

        # Conversii numerice
        for col in ['act_pwt','wd_spd','power_theoretical','power_efficiency_pct']:
            df[col] = pd.to_numeric(df[col], errors='coerce')
        df['status_color']  = df['status_color'].fillna('GREEN').astype(str).str.strip()
        df['timestamp_dt']  = pd.to_datetime(df['timestamp'], utc=True, errors='coerce')

        # ── CURĂȚARE DATE ─────────────────────────────────────────────────────
        # Elimină valori absolute imposibile (erori senzor majore)
        df_clean = df[
            df['act_pwt'].notna() &
            (df['act_pwt'] >= -10) &
            (df['act_pwt'] < 2_000_000)   # elimină outlieri extremi (ex: 2.4M kW)
        ].copy()
        df_clean.loc[df_clean['act_pwt'] < 0, 'act_pwt'] = 0

        # Elimina vânt=0 dar putere mare (fizic imposibil)
        df_clean = df_clean[
            ~((df_clean['wd_spd'].fillna(0) < 0.5) & (df_clean['act_pwt'] > 50))
        ]

        # Date valide pentru power curve (funcționale, non-anomalie)
        df_curve_valid = df_clean[
            (df_clean['is_anomaly'] == 0) &
            (df_clean['act_pwt'] >= 0) &
            df_clean.apply(
                lambda r: r['act_pwt'] <= NOMINAL_POWER_KW.get(r.get('fk_wtg_model_id','NEXUS'),1000) * 1.1,
                axis=1
            )
        ]

        FACTOR_MW  = 1000.0
        INTERVAL_H = 1.0 / 60.0  # 1 min/înregistrare
        df_clean['productie_mwh'] = (df_clean['act_pwt'] / FACTOR_MW) * INTERVAL_H

        # ── 1. RAPORT PARCURI ─────────────────────────────────────────────────
        rp = df_clean.groupby('park_id').agg(
            productie_mwh =('productie_mwh','sum'),
            nr_turbine    =('turbine_id','nunique'),
            avg_wind_ms   =('wd_spd','mean'),
            max_power_kw  =('act_pwt','max'),
            avg_power_kw  =('act_pwt','mean'),
        ).reset_index()
        rp = rp.merge(df_clean.groupby('park_id')['fk_wtg_model_id'].first().reset_index(), on='park_id', how='left')
        for c in ['productie_mwh','avg_wind_ms','max_power_kw','avg_power_kw']:
            rp[c] = rp[c].round(2)
        rp['zile_raport'] = zile_in_urma
        rp['generat_la']  = timp_acum.strftime('%Y-%m-%d %H:%M:%S')
        rp.to_sql('raport_zilnic_parcuri', conn, if_exists='replace', index=False)

        # ── 2. RAPORT MODELE ──────────────────────────────────────────────────
        rm = df_clean.groupby('fk_wtg_model_id').agg(
            productie_mwh=('productie_mwh','sum'),
            nr_turbine   =('turbine_id','nunique'),
            avg_wind_ms  =('wd_spd','mean'),
        ).reset_index()
        for c in ['productie_mwh','avg_wind_ms']:
            rm[c] = rm[c].round(2)
        rm.to_sql('raport_zilnic_modele', conn, if_exists='replace', index=False)

        # ── 3. RAPORT TURBINE DETALIAT ────────────────────────────────────────
        def last_notnull(x):
            v = x.dropna()
            return v.iloc[-1] if len(v) > 0 else None

        df_s = df_clean.sort_values(['park_id','turbine_id','timestamp'])
        rt = df_s.groupby(['park_id','turbine_id']).agg(
            productie_total_mwh =('productie_mwh','sum'),
            max_power_kw        =('act_pwt','max'),
            avg_power_kw        =('act_pwt','mean'),
            avg_wind_ms         =('wd_spd','mean'),
            avg_efficiency_pct  =('power_efficiency_pct','mean'),
            total_minute        =('status_color','count'),
            min_green           =('status_color', lambda x:(x=='GREEN').sum()),
            min_yellow          =('status_color', lambda x:(x=='YELLOW').sum()),
            min_orange          =('status_color', lambda x:(x=='ORANGE').sum()),
            min_red             =('status_color', lambda x:(x=='RED').sum()),
            status_curent       =('status_color', last_notnull),
            model               =('fk_wtg_model_id','first'),
        ).reset_index()

        rt['availability_pct'] = (
            (rt['total_minute'] - rt['min_red']) / rt['total_minute'] * 100
        ).clip(0, 100).round(2)
        rt['factor_capacitate_pct'] = rt.apply(
            lambda r: round(
                r['productie_total_mwh'] /
                max(NOMINAL_POWER_KW.get(r['model'],1000) / FACTOR_MW *
                    r['total_minute'] * INTERVAL_H, 0.0001) * 100, 2
            ), axis=1
        )
        for c in ['productie_total_mwh','max_power_kw','avg_power_kw','avg_wind_ms','avg_efficiency_pct']:
            rt[c] = rt[c].round(2)
        rt.drop(columns=['total_minute'], inplace=True)
        rt.to_sql('raport_zilnic_turbine', conn, if_exists='replace', index=False)

        # ── 4. CURBA DE PUTERE REALĂ vs OEM (bins 0.5 m/s) ───────────────────
        dc = df_curve_valid[df_curve_valid['wd_spd'].notna()].copy()
        dc['wind_bin'] = (dc['wd_spd'] / 0.5).round() * 0.5
        pc = dc.groupby(['fk_wtg_model_id','wind_bin']).agg(
            avg_power_real=('act_pwt','mean'),
            n             =('act_pwt','count'),
        ).reset_index()
        pc['avg_power_theoretical'] = pc.apply(
            lambda r: interpoleaza_oem(r['wind_bin'], r['fk_wtg_model_id']), axis=1
        )
        pc['avg_power_real']        = pc['avg_power_real'].round(1)
        pc['avg_power_theoretical'] = pc['avg_power_theoretical'].round(1)
        pc[pc['n'] >= 3].to_sql('raport_curba_putere', conn, if_exists='replace', index=False)

        # ── 5. DISTRIBUȚIE VÂNT (histogramă per parc) ─────────────────────────
        dw = df_clean[df_clean['wd_spd'].notna()].copy()
        dw['wind_bin'] = pd.cut(
            dw['wd_spd'], bins=list(range(0,26)), right=False,
            labels=[f"{i}-{i+1}" for i in range(0,25)]
        )
        wd = dw.groupby(['park_id','wind_bin'], observed=True).size().reset_index(name='frecventa')
        wd['wind_bin']       = wd['wind_bin'].astype(str)
        wd['frecventa_pct']  = wd.groupby('park_id')['frecventa'].transform(
            lambda x: (x / x.sum() * 100).round(2)
        )
        wd['wind_mid'] = wd['wind_bin'].apply(
            lambda b: float(b.split('-')[0]) + 0.5 if '-' in b else 0
        )
        wd.to_sql('raport_distributie_vant', conn, if_exists='replace', index=False)

        # ── 6. WIND ORAR (grafice temporale) ──────────────────────────────────
        df_clean['ora'] = df_clean['timestamp_dt'].dt.floor('h').dt.strftime('%Y-%m-%d %H:%M')
        wo = df_clean.groupby(['park_id','ora']).agg(
            avg_wind_ms  =('wd_spd','mean'),
            avg_power_kw =('act_pwt','mean'),
            productie_mwh=('productie_mwh','sum'),
        ).reset_index()
        for c in ['avg_wind_ms','avg_power_kw','productie_mwh']:
            wo[c] = wo[c].round(2)
        wo.to_sql('raport_wind_orar', conn, if_exists='replace', index=False)

        # ── 7. NOTIFICĂRI (ultimele events cu anomalii sau warnings) ──────────
        df_notif = df[
            (df['is_anomaly'] == 1) | (df['sensor_warnings'].fillna('') != '')
        ][['timestamp','park_id','turbine_id','status_color',
           'anomaly_reason','sensor_warnings','act_pwt','wd_spd']].copy()
        df_notif['act_pwt'] = pd.to_numeric(df_notif['act_pwt'], errors='coerce').round(1)
        df_notif['wd_spd']  = pd.to_numeric(df_notif['wd_spd'],  errors='coerce').round(2)
        df_notif.sort_values('timestamp', ascending=False).to_sql(
            'raport_notificari', conn, if_exists='replace', index=False
        )

        conn.close()
        print(f"✅ Rapoarte salvate:")
        print(f"   parcuri:         {len(rp)} | turbine: {len(rt)}")
        print(f"   curba_putere:    {len(pc)} | wind_orar: {len(wo)}")
        print(f"   distributie_vant:{len(wd)} | notificari: {len(df_notif)}")

    except Exception as e:
        print(f"❌ Eroare: {e}")
        traceback.print_exc()
        try: conn.close()
        except: pass


# =============================================================================
# PIPELINE PRINCIPAL
# =============================================================================
def proceseaza_datele_complete():
    try:
        repara_schema_bazei_de_date()
        fs = s3fs.S3FileSystem(**storage_options)

        # WTG metadata
        wtg_f  = fs.glob(S3_BASE_PATH.replace('s3://','') + "hackathon_wtg_data/*.parquet")
        if not wtg_f: return False
        df_wtg = pd.read_parquet("s3://"+wtg_f[0], storage_options=storage_options)[
            ['fk_park_id','fk_turbine_id','fk_wtg_model_id','lat_y','long_x']
        ]

        # Meteo
        mf = fs.glob(S3_BASE_PATH.replace('s3://','') + "hackathon_meteo_data/**/*.parquet")
        df_meteo = None
        for mfile in [mf[-1], mf[-2]]:
            try:
                df_meteo = pd.read_parquet("s3://"+mfile, storage_options=storage_options)
                break
            except Exception: time.sleep(15)
        if df_meteo is None: return False

        mt = df_meteo['fk_wtg_id'].str.split('.', expand=True)
        df_meteo['park_id']    = mt[0]
        df_meteo['turbine_id'] = mt[1]
        df_meteo['wind_speed_mean_5min'] = pd.to_numeric(df_meteo['wind_speed_mean_5min'], errors='coerce')
        df_meteo['timestamp_meteo'] = pd.to_datetime(
            df_meteo['data_timestamp'], unit='ms'
        ).dt.floor('min').dt.tz_localize(None)
        df_meteo = df_meteo.groupby(
            ['timestamp_meteo','park_id','turbine_id']
        )['wind_speed_mean_5min'].mean().reset_index()

        # Fișiere senzori
        print("- Caut fisiere noi...")
        zi1  = datetime.now().strftime('%Y-%m-%d')
        zi0  = (datetime.now()-timedelta(days=1)).strftime('%Y-%m-%d')
        pf   = fs.glob(S3_BASE_PATH.replace('s3://','')+"hackathon_park_data/**/*.parquet")
        rel  = [f for f in pf if zi1 in f or zi0 in f]

        try:
            ch = sqlite3.connect('../history.db')
            deja = pd.read_sql("SELECT DISTINCT timestamp FROM raw_history", ch)['timestamp'].tolist()
            ch.close()
        except: deja = []

        df_final = pd.DataFrame()
        new_cnt  = 0

        for f_s3 in rel:
            try:
                dh = pd.read_parquet("s3://"+f_s3, storage_options=storage_options)
                ts = pd.to_datetime(
                    dh['datetime_current_value'].iloc[0], unit='ms'
                ).floor('min').tz_localize(None).strftime('%Y-%m-%d %H:%M:%S')
                if ts in deja: continue
                new_cnt += 1
                print(f"  > {f_s3.split('/')[-1]}")

                tags = dh['tag_name'].str.split('.', expand=True)
                dh['park_id']    = tags[0]
                dh['turbine_id'] = tags[1]
                dh['sensor']     = tags[2]

                err = (dh['current_value'].notna()) & (dh['current_value'] % 1 == 0)
                dh.loc[err, 'current_value'] = np.nan
                dh['timestamp'] = pd.to_datetime(
                    dh['datetime_current_value'], unit='ms'
                ).dt.floor('min').dt.tz_localize(None)

                dp = dh.pivot_table(
                    index=['timestamp','park_id','turbine_id'],
                    columns='sensor', values='current_value', aggfunc='mean'
                ).reset_index()

                ds = pd.merge(dp, df_meteo,
                    left_on=['timestamp','park_id','turbine_id'],
                    right_on=['timestamp_meteo','park_id','turbine_id'], how='left')
                if 'wd_spd' in ds.columns and 'wind_speed_mean_5min' in ds.columns:
                    ds['wd_spd'] = ds['wd_spd'].fillna(ds['wind_speed_mean_5min'])
                ds.drop(columns=['timestamp_meteo','wind_speed_mean_5min'], inplace=True, errors='ignore')

                ds = pd.merge(ds, df_wtg,
                    left_on=['park_id','turbine_id'],
                    right_on=['fk_park_id','fk_turbine_id'], how='left')
                ds.drop(columns=['fk_park_id','fk_turbine_id'], inplace=True, errors='ignore')

                if 'wd_spd' in ds.columns and 'act_pwt' in ds.columns:
                    ds = calculeaza_eficienta_si_teoreticul(ds)

                # Anomalii Z-score
                ds['sensor_warnings'] = ""
                try:
                    ch = sqlite3.connect('../history.db')
                    tm = ds['timestamp'].max()
                    t3 = (tm - pd.Timedelta(hours=3)).strftime('%Y-%m-%d %H:%M:%S')
                    exc = {'timestamp','park_id','turbine_id','fk_wtg_model_id',
                           'lat_y','long_x','status_color','anomaly_reason',
                           'is_anomaly','sensor_warnings','thermal_risk'}
                    sc = [c for c in ds.columns if c not in exc
                          and pd.api.types.is_numeric_dtype(ds[c])]
                    if sc:
                        csql  = "park_id,turbine_id," + ",".join(sc)
                        dh3   = pd.read_sql(
                            f"SELECT {csql} FROM raw_history WHERE timestamp>='{t3}'", ch
                        )
                        if not dh3.empty:
                            for c in sc: dh3[c] = pd.to_numeric(dh3[c], errors='coerce')
                            st = dh3.groupby(['park_id','turbine_id'])[sc].agg(['mean','std']).reset_index()
                            st.columns = ['park_id','turbine_id'] + [
                                f"{c}_{s}" for c in sc for s in ['mean','std']
                            ]
                            ds = pd.merge(ds, st, on=['park_id','turbine_id'], how='left')
                            for c in sc:
                                mc,sc2 = f"{c}_mean", f"{c}_std"
                                if mc in ds.columns and sc2 in ds.columns:
                                    spk = np.abs(ds[c]-ds[mc]) > (3*ds[sc2].fillna(0)+0.5)
                                    ds.loc[spk,'sensor_warnings'] += f"⚠️ {c}; "
                            ds.drop(columns=[c for c in ds.columns
                                if c.endswith('_mean') or c.endswith('_std')], inplace=True)
                    ch.close()
                except: pass

                # Anomalii critice
                cond_a = [
                    ds['act_pwt'].isna(),
                    ds['act_pwt'] < -50,
                    ds.apply(lambda r: r.get('act_pwt',0) >
                        NOMINAL_POWER_KW.get(r.get('fk_wtg_model_id','NEXUS'),1000)*1.5, axis=1),
                    (ds.get('wd_spd', pd.Series(0,index=ds.index)) > 7) &
                    (ds.get('power_efficiency_pct', pd.Series(0,index=ds.index)) < 10)
                ]
                ds['is_anomaly']     = np.select(cond_a,[1,1,1,1],default=0)
                ds['anomaly_reason'] = np.select(
                    cond_a,
                    ["Eroare senzor","Scurtcircuit","Peste limite","Eficienta scazuta"],
                    default="Functionare normala"
                )

                ds = calculeaza_status(ds)

                # Risc termic
                tc = [c for c in ['conv_t','gearbox_t','gen1_t','gen2_t','transformer_t','turbine_t']
                      if c in ds.columns]
                if tc:
                    ds['thermal_risk'] = ds.apply(
                        lambda r: calculeaza_risc_termic(r.to_dict())[0], axis=1
                    )
                else:
                    ds['thermal_risk'] = None

                ch = sqlite3.connect('../history.db')
                ds['timestamp'] = pd.to_datetime(ds['timestamp']).dt.strftime('%Y-%m-%d %H:%M:%S')
                ds.to_sql('raw_history', ch, if_exists='append', index=False)
                ch.close()
                df_final = ds

            except Exception as e:
                print(f"  ! Eroare: {e}")

        if new_cnt == 0:
            print("- Niciun fisier nou.")
            return True

        if not df_final.empty:
            ch = sqlite3.connect('../history.db')
            tc = pd.to_datetime(df_final['timestamp'].max())

            # Yellow→Red (2h fără producție)
            t2 = (tc - pd.Timedelta(hours=2)).strftime('%Y-%m-%d %H:%M:%S')
            try:
                d2 = pd.read_sql(
                    f"SELECT park_id,turbine_id,act_pwt FROM raw_history WHERE timestamp>='{t2}'", ch
                )
                if not d2.empty:
                    med = d2.groupby(['park_id','turbine_id']).mean(numeric_only=True).reset_index()
                    mort = med[med['act_pwt'] <= 0]['turbine_id'].tolist()
                    df_final.loc[
                        (df_final['status_color']=='YELLOW') & df_final['turbine_id'].isin(mort),
                        'status_color'
                    ] = 'RED'
            except: pass

            # Availability 24h
            t24 = (tc - pd.Timedelta(hours=24)).strftime('%Y-%m-%d %H:%M:%S')
            try:
                d24 = pd.read_sql(
                    f"SELECT park_id,turbine_id,status_color FROM raw_history WHERE timestamp>='{t24}'", ch
                )
                if not d24.empty:
                    av = d24.groupby(['park_id','turbine_id']).agg(
                        tot=('status_color','count'),
                        ok =('status_color', lambda x:(x!='RED').sum())
                    )
                    av['availability_pct'] = (av['ok']/av['tot']*100).round(1)
                    av = av.reset_index()[['park_id','turbine_id','availability_pct']]
                    df_final = pd.merge(df_final, av, on=['park_id','turbine_id'], how='left')
                    df_final['availability_pct'] = df_final['availability_pct'].fillna(100.0)
            except Exception: pass

            if 'availability_pct' not in df_final.columns:
                df_final['availability_pct'] = 100.0
            ch.close()

            cl = sqlite3.connect('../ppc_energy.db')
            df_final.to_sql('live_measurements', cl, if_exists='replace', index=False)
            cl.close()
            print(f"✅ LIVE actualizat: {time.ctime()}")

        return True

    except Exception as e:
        print(f"⚠️ Eroare S3: {e}")
        traceback.print_exc()
        return False


def job_principal():
    while True:
        print(f"\n{'='*60}\n⏳ CICLU: {time.ctime()}\n{'='*60}")
        ok = proceseaza_datele_complete()
        while not ok:
            print("  Reîncerc în 30s...")
            time.sleep(30)
            ok = proceseaza_datele_complete()

        tr = '../report_trigger.txt'
        zile = 1
        if os.path.exists(tr):
            try:
                with open(tr) as f: zile = max(1, int(f.read().strip()))
                os.remove(tr)
                print(f"📋 Trigger: {zile} zile")
            except: zile = 1

        genereaza_raport_operational(zile_in_urma=zile)
        print("  Următor ciclu în 15 min...")
        time.sleep(900)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument('--report', type=int, default=None,
                        help='Generează raport pentru N zile (ex: --report 7)')
    args = parser.parse_args()
    if args.report:
        repara_schema_bazei_de_date()
        genereaza_raport_operational(zile_in_urma=args.report)
    else:
        job_principal()