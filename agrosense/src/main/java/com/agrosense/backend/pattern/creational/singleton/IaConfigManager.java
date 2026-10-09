package com.agrosense.backend.pattern.creacional.singleton;


public class IaConfigManager {

    // volatile garantiza visibilidad entre hilos
    private static volatile IaConfigManager instance;

    private String  iaServiceUrl;
    private int     timeoutMs;
    private boolean habilitate;
    private int     maxattempts;

    private IaConfigManager() {
        this.iaServiceUrl  = "http://localhost:9000";
        this.timeoutMs     = 5000;
        this.habilitate    = true;
        this.maxattempts = 3;
    }

    /**
     * Double-checked locking para seguridad en entornos multihilo.
     */
    public static IaConfigManager getInstance() {
        if (instance == null) {
            synchronized (IaConfigManager.class) {
                if (instance == null) {
                    instance = new IaConfigManager();
                }
            }
        }
        return instance;
    }

    // Métodos de configuración
    public void configurar(String url, int timeout,
                           boolean habilitate, int reintentos) {
        this.iaServiceUrl  = url;
        this.timeoutMs     = timeout;
        this.habilitate    = habilitate;
        this.maxattempts = attemps;
    }

    public String  getIaServiceUrl()  { return iaServiceUrl;  }
    public int     getTimeoutMs()     { return timeoutMs;     }
    public boolean isHabilitate()     { return habilitate;    }
    public int     getMaxattemps() { return maxattemps; }
}