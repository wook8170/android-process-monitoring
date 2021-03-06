package com.appmon.control.models.user;

import android.support.annotation.Nullable;

import com.appmon.shared.DatabaseError;
import com.appmon.shared.IAuthService;
import com.appmon.shared.ICloudServices;
import com.appmon.shared.IDatabaseService;
import com.appmon.shared.IUser;
import com.appmon.shared.ResultListener;
import com.appmon.shared.exceptions.AuthEmailTakenException;
import com.appmon.shared.exceptions.AuthException;
import com.appmon.shared.exceptions.AuthInvalidEmailException;
import com.appmon.shared.exceptions.AuthWeakPasswordException;
import com.appmon.shared.exceptions.AuthWrongPasswordException;
import com.appmon.shared.utils.Validator;

import java.util.HashSet;
import java.util.Set;

/*
 * NOTE: Model implementation at the moment is at most dummy,
 * it just validates input for format correctness
 */
public class UserModel implements IUserModel {
    private final String LOG_TAG = "UserModel";
    private final String PREFERENCES_APP_PIN_KEY = "app_pin";
    // listener sets
    private Set<IRegisterListener> mRegisterListeners = new HashSet<>();
    private Set<ISignInListener> mSignInListeners = new HashSet<>();
    private Set<IChangePasswordListener> mChangePasswordListeners = new HashSet<>();
    private Set<IChangeAppPinListener> mChangeAppPinListeners = new HashSet<>();
    private Set<IChangeClientPinListener> mChangeClientPinListener = new HashSet<>();
    private Set<IResetPasswordListener> mResetPasswordListeners = new HashSet<>();

    private final IPreferences mPreferences;
    private final IDatabaseService mDatabase;
    private final IAuthService mAuth;
    private String mUserRootPath;
    private IUser mUser;

    public UserModel(ICloudServices cloudServices, IPreferences preferences) {
        mPreferences = preferences;
        mDatabase = cloudServices.getDatabase();
        mAuth = cloudServices.getAuth();
        updateUserInfo();
    }

    /**
     * updates toot path of user data
     */
    private void updateUserInfo() {
        mUser = mAuth.getUser();
        if (mUser != null) {
            mUserRootPath = mUser.getUserID() + "/";
        } else {
            mUserRootPath = null;
        }
    }

    /**
     * Transforms error, returned by auth service to enum
     * @param ex error to transform
     * @return transformed error
     */
    private RegisterError authExceptionToRegisterError(Throwable ex) {
        try {
            throw ex;
        } catch (AuthEmailTakenException e) {
            return RegisterError.USER_EXISTS;
        } catch (AuthInvalidEmailException e) {
            return RegisterError.INVALID_EMAIL;
        } catch (AuthWeakPasswordException e) {
            return RegisterError.WEAK_PASSWORD;
        } catch (AuthException e) {
            return RegisterError.INTERNAL_ERROR;
        } catch (Throwable e) {
            return RegisterError.INTERNAL_ERROR;
        }
    }

    /**
     * Transforms error, returned by auth service to enum
     * @param ex error to transform
     * @return transformed error
     */
    private SignInError authExceptionToSignInError(Throwable ex) {
        try {
            throw ex;
        } catch (AuthInvalidEmailException e) {
            return SignInError.INVALID_EMAIL;
        } catch (AuthWrongPasswordException e) {
            return SignInError.WRONG_PASSWORD;
        } catch (AuthException e) {
            return  SignInError.INTERNAL_ERROR;
        } catch (Throwable e) {
            return  SignInError.INTERNAL_ERROR;
        }
    }

    @Override
    public void signOut() {
        mAuth.signOut();
        mUser = null;
        mUserRootPath = null;
    }

    @Override
    public void registerWithEmail(String email, String password) {
        // early validation before firebase request
        // determine error and notify all listeners if it happened
        RegisterError err = null;
        if (!Validator.validateEmail(email)) {
            err = RegisterError.INVALID_EMAIL;
        } else if (!Validator.validatePassword(password)) {
            err = RegisterError.WEAK_PASSWORD;
        }
        if (err != null) {
            for (IRegisterListener l : mRegisterListeners) {
                l.onFail(err);
            }
            return;
        }
        mAuth.registerWithEmail(email, password, new ResultListener<IUser, Throwable>() {
            @Override
            public void onSuccess(IUser value) {
                updateUserInfo();
                for (IRegisterListener l : mRegisterListeners) {
                    l.onSuccess();
                }
            }

            @Override
            public void onFailure(Throwable error) {
                RegisterError registerError = authExceptionToRegisterError(error);
                for (IRegisterListener l : mRegisterListeners) {
                    l.onFail(registerError);
                }
            }
        });
    }

    @Override
    public void signInWithEmail(String email, String password) {
        // early validation before firebase request
        SignInError err = null;
        if (!Validator.validateEmail(email)) {
            err = SignInError.INVALID_EMAIL;
        } else if (!Validator.validatePassword(password)) {
            err = SignInError.WRONG_PASSWORD;
        }
        if (err != null) {
            for (ISignInListener l : mSignInListeners) {
                l.onFail(err);
            }
            return;
        }
        mAuth.signInWithEmail(email, password, new ResultListener<IUser, Throwable>() {
            @Override
            public void onSuccess(IUser value) {
                updateUserInfo();
                for (ISignInListener l : mSignInListeners) {
                    l.onSuccess();
                }
            }

            @Override
            public void onFailure(Throwable error) {
                SignInError signInError = authExceptionToSignInError(error);
                for (ISignInListener l : mSignInListeners) {
                    l.onFail(signInError);
                }
            }
        });
    }

    @Override
    public void changePassword(String password) {
        // early validation
        if (!Validator.validatePassword(password)) {
            for (IChangePasswordListener l : mChangePasswordListeners) {
                l.onFail(ChangePasswordError.WEAK_PASSWORD);
            }
            return;
        }
        // ignore on signed out user
        if (mUser == null) {
            return;
        }
        mUser.changePassword(password, new ResultListener<Void, IUser.ChangePasswordError>() {
            @Override
            public void onSuccess(Void value) {
                for (IChangePasswordListener l : mChangePasswordListeners) {
                    l.onSuccess();
                }
            }

            @Override
            public void onFailure(IUser.ChangePasswordError error) {
                for (IChangePasswordListener l : mChangePasswordListeners) {
                    switch (error) {
                        case WEAK_PASSWORD:
                            l.onFail(ChangePasswordError.WEAK_PASSWORD);
                            break;
                        case REAUTH_NEEDED:
                            l.onFail(ChangePasswordError.REAUTH_NEEDED);
                            break;
                        default:
                            l.onFail(ChangePasswordError.INTERNAL_ERROR);
                    }
                }
            }
        });
    }

    @Override
    public void changeAppPin(String pin) {
        // validation
        if (pin == null || pin.isEmpty()) {
            mPreferences.remove(PREFERENCES_APP_PIN_KEY);
        } else {
            if (!Validator.validatePin(pin)) {
                for (IChangeAppPinListener l : mChangeAppPinListeners) {
                    l.onFail(ChangeAppPinError.WEAK_PIN);
                }
                return;
            }
            mPreferences.setString(PREFERENCES_APP_PIN_KEY, pin);
        }
        for (IChangeAppPinListener l : mChangeAppPinListeners) {
            l.onSuccess();
        }
    }

    @Nullable
    @Override
    public String getAppPin() {
        return mPreferences.getString(PREFERENCES_APP_PIN_KEY);
    }

    @Override
    public void changeClientPin(String pin) {
        // early validation
        if (!Validator.validatePin(pin)) {
            for (IChangeClientPinListener l : mChangeClientPinListener) {
                l.onFail(ChangeClientPinError.WEAK_PIN);
            }
            return;
        }
        mDatabase.goOnline(); // can be turned off by device list model
        mDatabase.setValue(mUserRootPath + "pin", pin, new ResultListener<Void, DatabaseError>() {
            @Override
            public void onSuccess(Void value) {
                for (IChangeClientPinListener l : mChangeClientPinListener) {
                    l.onSuccess();
                }
            }

            @Override
            public void onFailure(DatabaseError error) {
                for (IChangeClientPinListener l : mChangeClientPinListener) {
                    l.onFail(ChangeClientPinError.INTERNAL_ERROR);
                }
            }
        });
    }

    @Override
    public void resetPassword(String email) {
        if (!Validator.validateEmail(email)) {
            for (IResetPasswordListener l : mResetPasswordListeners) {
                l.onFail(ResetPasswordError.INVALID_USER);
            }
            return;
        }
        mAuth.resetPassword(email, new ResultListener<Void, Throwable>() {
            @Override
            public void onSuccess(Void value) {
                for (IResetPasswordListener l: mResetPasswordListeners) {
                    l.onSuccess();
                }
            }

            @Override
            public void onFailure(Throwable error) {
                for (IResetPasswordListener l: mResetPasswordListeners) {
                    l.onFail(ResetPasswordError.INTERNAL_ERROR);
                }
            }
        });
    }

    @Nullable
    @Override
    public String getUserID() {
        if (mUser != null) {
            return mUser.getUserID();
        }
        return null;
    }

    //======= Listeners Add\Remove operations

    @Override
    public void addSignInListener(ISignInListener listener) {
        mSignInListeners.add(listener);
    }

    @Override
    public void addRegisterListener(IRegisterListener listener) {
        mRegisterListeners.add(listener);
    }

    @Override
    public void addChangePasswordListener(IChangePasswordListener listener) {
        mChangePasswordListeners.add(listener);
    }

    @Override
    public void addChangeAppPinListener(IChangeAppPinListener listener) {
        mChangeAppPinListeners.add(listener);
    }

    @Override
    public void addChangeClientPinListener(IChangeClientPinListener listener) {
        mChangeClientPinListener.add(listener);
    }

    @Override
    public void addResetPasswordListener(IResetPasswordListener listener) {
        mResetPasswordListeners.add(listener);
    }

    @Override
    public void removeSignInListener(ISignInListener listener) {
        mSignInListeners.remove(listener);
    }

    @Override
    public void removeRegisterListener(IRegisterListener listener) {
        mRegisterListeners.remove(listener);
    }

    @Override
    public void removeChangePasswordListener(IChangePasswordListener listener) {
        mChangePasswordListeners.remove(listener);
    }

    @Override
    public void removeChangeAppPinListener(IChangeAppPinListener listener) {
        mChangeAppPinListeners.remove(listener);
    }

    @Override
    public void removeChangeClientPinListener(IChangeClientPinListener listener) {
        mChangeClientPinListener.remove(listener);
    }

    @Override
    public void removeResetPasswordListener(IResetPasswordListener listener) {
        mResetPasswordListeners.remove(listener);
    }
}
