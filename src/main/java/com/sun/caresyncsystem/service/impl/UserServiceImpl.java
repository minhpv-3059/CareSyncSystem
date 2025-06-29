package com.sun.caresyncsystem.service.impl;

import com.sun.caresyncsystem.configuration.AppProperties;
import com.sun.caresyncsystem.dto.request.ReviewDoctorRegistrationRequest;
import com.sun.caresyncsystem.dto.request.CreateUserRequest;
import com.sun.caresyncsystem.dto.request.UpdateUserActiveRequest;
import com.sun.caresyncsystem.dto.response.UserResponse;
import com.sun.caresyncsystem.exception.AppException;
import com.sun.caresyncsystem.exception.ErrorCode;
import com.sun.caresyncsystem.exception.ValidationError;
import com.sun.caresyncsystem.mapper.ToDtoMappers;
import com.sun.caresyncsystem.model.entity.Doctor;
import com.sun.caresyncsystem.model.entity.Patient;
import com.sun.caresyncsystem.model.entity.User;
import com.sun.caresyncsystem.model.entity.VerificationToken;
import com.sun.caresyncsystem.model.enums.UserRole;
import com.sun.caresyncsystem.repository.DoctorRepository;
import com.sun.caresyncsystem.repository.PatientRepository;
import com.sun.caresyncsystem.repository.UserRepository;
import com.sun.caresyncsystem.repository.VerificationTokenRepository;
import com.sun.caresyncsystem.service.EmailService;
import com.sun.caresyncsystem.service.PasswordService;
import com.sun.caresyncsystem.service.UserService;
import com.sun.caresyncsystem.utils.api.AuthApiPaths;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@AllArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
    private final VerificationTokenRepository tokenRepository;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final PasswordService passwordService;
    private final EmailService emailService;
    private final AppProperties appProperties;

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        if (userRepository.existsUserByEmail(request.email()))
            throw new AppException(ErrorCode.USER_EXISTED);

        User user = User.builder()
                .fullName(request.fullName())
                .email(request.email())
                .phone(request.phone())
                .address(request.address())
                .gender(request.gender())
                .avatarUrl(request.avatarUrl())
                .dateOfBirth(request.dateOfBirth())
                .password(passwordService.encodePassword(request.password()))
                .isVerified(false)
                .isActive(false)
                .role(request.role())
                .build();

        User savedUser = userRepository.save(user);
        switch (request.role()) {
            case PATIENT -> {
                validatePatientInfo(request);

                Patient patient = Patient.builder()
                        .user(savedUser)
                        .insuranceNumber(request.insuranceNumber())
                        .nationalId(request.nationalId())
                        .medicalHistory(request.medicalHistory())
                        .build();
                patientRepository.save(patient);

                String token = UUID.randomUUID().toString();
                VerificationToken verificationToken = VerificationToken.builder()
                        .token(token)
                        .user(savedUser)
                        .expiryDate(LocalDateTime.now().plusHours(1))
                        .build();
                tokenRepository.save(verificationToken);

                String activationLink = UriComponentsBuilder
                        .fromUriString(appProperties.getBaseUrl())
                        .path(AuthApiPaths.Endpoint.FULL_ACTIVATE)
                        .queryParam("token", token)
                        .build()
                        .toUriString();

                emailService.sendActivationEmail(savedUser.getEmail(), savedUser.getFullName(), activationLink);

                return ToDtoMappers.toUserResponse(savedUser, patient);
            }

            case DOCTOR -> {
                validateDoctorInfo(request);
                savedUser.setApproved(false);
                Doctor doctor = Doctor.builder()
                        .user(savedUser)
                        .department(request.department())
                        .specialization(request.specialization())
                        .bio(request.bio())
                        .ratingAvg(0.0f)
                        .build();
                savedUser.setDoctor(doctor);

                doctorRepository.save(doctor);

                emailService.sendPendingApprovalEmail(
                        savedUser.getEmail(),
                        savedUser.getFullName()
                );

                return ToDtoMappers.toUserResponse(savedUser, doctor);
            }

            default -> throw new AppException(ErrorCode.ROLE_NOT_ALLOWED);
        }
    }

    public Page<UserResponse> getPendingDoctors(Pageable pageable) {
        Page<Doctor> doctors = doctorRepository.findByUserIsApprovedFalseAndUserDeletedAtIsNull(pageable);

        return doctors.map(doctor -> ToDtoMappers.toUserResponse(doctor.getUser(), doctor));
    }

    public UserResponse getUserByUserId(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND_FROM_TOKEN));

        return switch (user.getRole()) {
            case DOCTOR -> {
                Doctor doctor = doctorRepository.findByUserId(user.getId())
                        .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND_FROM_TOKEN));
                yield ToDtoMappers.toUserResponse(user, doctor);
            }
            case PATIENT -> {
                Patient patient = patientRepository.findByUserId(user.getId())
                        .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND_FROM_TOKEN));
                yield ToDtoMappers.toUserResponse(user, patient);
            }
            default -> throw new AppException(ErrorCode.UNAUTHORIZED);
        };
    }

    @Transactional
    public void reviewDoctorRegistration(Long userId, ReviewDoctorRegistrationRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXIST));

        if (!user.getRole().equals(UserRole.DOCTOR)) {
            throw new AppException(ErrorCode.ROLE_NOT_ALLOWED);
        }

        if (user.isApproved()) {
            throw new AppException(ErrorCode.DOCTOR_ALREADY_APPROVED);
        }

        if (user.getDeletedAt() != null) {
            throw new AppException(ErrorCode.DOCTOR_ALREADY_REJECTED);
        }

        if (Boolean.TRUE.equals(request.isApproved())) {
            user.setApproved(true);

            String token = UUID.randomUUID().toString();
            VerificationToken verificationToken = VerificationToken.builder()
                    .token(token)
                    .user(user)
                    .expiryDate(LocalDateTime.now().plusHours(1))
                    .build();
            tokenRepository.save(verificationToken);

            String activationLink = UriComponentsBuilder
                    .fromUriString(appProperties.getBaseUrl())
                    .path(AuthApiPaths.Endpoint.FULL_ACTIVATE)
                    .queryParam("token", token)
                    .build()
                    .toUriString();

            emailService.sendActivationEmail(user.getEmail(), user.getFullName(), activationLink);

        } else {
            user.setDeletedAt(LocalDateTime.now());

            emailService.sendRejectDoctorEmail(
                    user.getEmail(),
                    user.getFullName(),
                    request.rejectReason()
            );
        }

        userRepository.save(user);
    }

    public Page<UserResponse> getAllUsers(Pageable pageable) {
        Page<User> users = userRepository.findByRoleIn(List.of(UserRole.DOCTOR, UserRole.PATIENT), pageable);

        return users.map(user -> {
            switch (user.getRole()) {
                case DOCTOR -> {
                    Doctor doctor = doctorRepository.findByUserId(user.getId())
                            .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND_FROM_TOKEN));
                    return ToDtoMappers.toUserResponse(user, doctor);
                }
                case PATIENT -> {
                    Patient patient = patientRepository.findByUserId(user.getId())
                            .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND_FROM_TOKEN));
                    return ToDtoMappers.toUserResponse(user, patient);
                }
                default -> throw new AppException(ErrorCode.UNAUTHORIZED);
            }
        });
    }

    @Transactional
    public void updateUserActiveStatus(Long userId, UpdateUserActiveRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXIST));

        if (user.isActive() == request.isActive()) {
            throw new AppException(user.isActive()
                    ? ErrorCode.ACCOUNT_ALREADY_ACTIVE
                    : ErrorCode.ACCOUNT_ALREADY_DEACTIVATE);
        }

        user.setActive(request.isActive());
        userRepository.save(user);
        if (request.isActive()) {
            emailService.sendActivationEmailFromAdmin(user.getEmail(), user.getFullName());
        } else {
            emailService.sendAccountDeactivatedEmail(user.getEmail(), user.getFullName());
        }
    }

    private void validatePatientInfo(CreateUserRequest request) {
        if (!StringUtils.hasText(request.insuranceNumber()) ||
                !StringUtils.hasText(request.nationalId())) {
            throw new AppException(ValidationError.PATIENT_INFO_REQUIRED);
        }
    }

    private void validateDoctorInfo(CreateUserRequest request) {
        if (!StringUtils.hasText(request.department()) ||
                !StringUtils.hasText(request.specialization())) {
            throw new AppException(ValidationError.DOCTOR_INFO_REQUIRED);
        }
    }
}
